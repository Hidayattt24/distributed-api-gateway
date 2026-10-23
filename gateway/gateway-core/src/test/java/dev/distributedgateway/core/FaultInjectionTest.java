package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class FaultInjectionTest {
    @Test
    void rejectsWhenInFlightCapacityIsFull() throws Exception {
        GatewayMetrics metrics = new GatewayMetrics();
        for (int index = 0; index < 256; index++) {
            metrics.requestStarted();
        }
        GatewayProxy gateway = new GatewayProxy(
                List.of(new GatewayRoute("demo", "/demo", URI.create("http://localhost:1"))),
                Duration.ofSeconds(3), java.util.Map.of(), new LocalTokenBucketRateLimiter(),
                new LoadBalancer(), new ApiKeyVerifier(java.util.Map.of()), metrics, new AccessLogger(s -> {}));
        DisposableServer server = gateway.start(0);
        try {
            var response = java.net.http.HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + server.port() + "/demo")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertEquals("1", response.headers().firstValue("Retry-After").orElseThrow());
        } finally {
            server.disposeNow();
            for (int index = 0; index < 256; index++) {
                metrics.requestFinished();
            }
        }
    }

    @Test
    void drainRejectsNewRequestsWhileExistingResponseCompletesAndMeasuresFullDuration() throws Exception {
        DisposableServer backend = HttpServer.create().port(0).handle((req, resp) ->
                resp.sendString(Flux.concat(Mono.just("first"),
                        Mono.delay(Duration.ofMillis(350)).map(ignored -> "last")))).bindNow();
        GatewayMetrics metrics = new GatewayMetrics();
        GatewayProxy gateway = new GatewayProxy(List.of(new GatewayRoute("stream", "/stream",
                URI.create("http://localhost:" + backend.port()))), Duration.ofSeconds(3),
                java.util.Map.of(), new LocalTokenBucketRateLimiter(), new LoadBalancer(),
                new ApiKeyVerifier(java.util.Map.of()), metrics, new AccessLogger(s -> {}));
        DisposableServer server = gateway.start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            URI uri = URI.create("http://localhost:" + server.port() + "/stream");
            var first = client.sendAsync(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (metrics.inFlightCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(1, metrics.inFlightCount());
            gateway.beginDrain();
            var rejected = client.send(HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            assertEquals(200, first.get(3, TimeUnit.SECONDS).statusCode());
            long drainedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (metrics.inFlightCount() != 0 && System.nanoTime() < drainedDeadline) {
                Thread.sleep(10);
            }
            assertEquals(0, metrics.inFlightCount());
            var timer = metrics.registry().find("gateway_request_duration_seconds")
                    .tag("route", "stream").tag("status_class", "2xx").timer();
            assertTrue(timer != null && timer.totalTime(TimeUnit.MILLISECONDS) >= 300);
        } finally {
            server.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void measuresTimeoutFaultAndRecordsMetrics() throws Exception {
        DisposableServer delayedBackend = HttpServer.create().port(0).handle((req, resp) ->
                resp.sendString(Mono.delay(Duration.ofSeconds(2)).map(v -> "too late"))
        ).bindNow();

        GatewayMetrics metrics = new GatewayMetrics();
        GatewayRoute route = new GatewayRoute("slow-service", "/slow", URI.create("http://localhost:" + delayedBackend.port()));
        GatewayProxy gateway = new GatewayProxy(List.of(route), Duration.ofMillis(200),
                java.util.Map.of(), new LocalTokenBucketRateLimiter(), new LoadBalancer(),
                new ApiKeyVerifier(java.util.Map.of()), metrics, new AccessLogger(s -> {}));
        DisposableServer server = gateway.start(0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/slow/test")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(504, response.statusCode());

            String scrape = metrics.scrape();
            assertTrue(scrape.contains("gateway_upstream_failures_total"));
            assertTrue(scrape.contains("reason=\"timeout\""));
            assertTrue(scrape.contains("slow-service"));
        } finally {
            server.disposeNow();
            delayedBackend.disposeNow();
        }
    }

    @Test
    void measuresUpstreamDisconnectCooldownAndRecovery() throws Exception {
        AtomicLong simulatedClock = new AtomicLong(1_000_000_000L);
        LoadBalancer loadBalancer = new LoadBalancer(Duration.ofSeconds(5), simulatedClock::get);
        GatewayMetrics metrics = new GatewayMetrics();

        AtomicBoolean backendHealthy = new AtomicBoolean(true);
        DisposableServer backend = HttpServer.create().port(0).handle((req, resp) -> {
            if (!backendHealthy.get()) {
                return resp.status(500).sendString(Mono.just("error"));
            }
            return resp.sendString(Mono.just("healthy-response"));
        }).bindNow();

        int port = backend.port();
        GatewayRoute route = new GatewayRoute("flaky", "/flaky", URI.create("http://localhost:" + port));
        GatewayProxy gateway = new GatewayProxy(List.of(route), Duration.ofSeconds(3),
                java.util.Map.of(), new LocalTokenBucketRateLimiter(), loadBalancer,
                new ApiKeyVerifier(java.util.Map.of()), metrics, new AccessLogger(s -> {}));
        DisposableServer server = gateway.start(0);
        DisposableServer management = gateway.startManagement("127.0.0.1", 0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            // 1. Initial healthy request
            var ok = client.send(HttpRequest.newBuilder(URI.create(base + "/flaky/test")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertEquals("healthy-response", ok.body());

            // Check health endpoint
            String managementBase = "http://localhost:" + management.port();
            var health = client.send(HttpRequest.newBuilder(URI.create(managementBase + "/_gateway/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertTrue(health.body().contains("\"UP\""));

            // 2. Kill backend to simulate network partition / crash
            backend.disposeNow();

            var failed = client.send(HttpRequest.newBuilder(URI.create(base + "/flaky/test")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, failed.statusCode());

            // Check health status in metrics & health endpoint
            String scrape = metrics.scrape();
            assertTrue(scrape.contains("gateway_upstream_health"));
            assertTrue(scrape.contains("reason=\"unavailable\""));

            var unhealthCheck = client.send(HttpRequest.newBuilder(URI.create(managementBase + "/_gateway/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, unhealthCheck.statusCode());
            assertTrue(unhealthCheck.body().contains("\"DOWN\""));

            // 3. Re-start backend on same port (simulated recovery)
            DisposableServer restoredBackend = HttpServer.create().port(port).handle((req, resp) ->
                    resp.sendString(Mono.just("recovered"))
            ).bindNow();

            try {
                // Advance simulated clock past 5 second cooldown
                simulatedClock.addAndGet(6_000_000_000L);

                var recovered = client.send(HttpRequest.newBuilder(URI.create(base + "/flaky/test")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, recovered.statusCode());
                assertEquals("recovered", recovered.body());

                var recoveredHealth = client.send(HttpRequest.newBuilder(URI.create(managementBase + "/_gateway/health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, recoveredHealth.statusCode());
                assertTrue(recoveredHealth.body().contains("\"UP\""));
            } finally {
                restoredBackend.disposeNow();
            }
        } finally {
            management.disposeNow();
            server.disposeNow();
        }
    }

    @Test
    void exposesMetricsEndpointInPrometheusFormat() throws Exception {
        DisposableServer backend = HttpServer.create().port(0).handle((req, resp) ->
                resp.sendString(Mono.just("ok"))
        ).bindNow();

        GatewayRoute route = new GatewayRoute("demo", "/demo", URI.create("http://localhost:" + backend.port()));
        GatewayProxy gateway = new GatewayProxy(List.of(route), Duration.ofSeconds(3));
        DisposableServer server = gateway.start(0);
        DisposableServer management = gateway.startManagement("127.0.0.1", 0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();

            client.send(HttpRequest.newBuilder(URI.create(base + "/demo/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            var publicMetrics = client.send(HttpRequest.newBuilder(URI.create(base + "/_gateway/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, publicMetrics.statusCode());
            var metricsRes = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                    + management.port() + "/_gateway/metrics")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, metricsRes.statusCode());
            assertTrue(metricsRes.headers().firstValue("Content-Type").orElse("").contains("text/plain"));
            assertTrue(metricsRes.body().contains("gateway_requests_total"));
            assertTrue(metricsRes.body().contains("route=\"demo\""));
        } finally {
            management.disposeNow();
            server.disposeNow();
            backend.disposeNow();
        }
    }
}
