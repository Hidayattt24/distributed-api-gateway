package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class GatewayProxyTest {
    @Test
    void forwardsGetPostAndUnmatchedRoute() throws Exception {
        DisposableServer backend = HttpServer.create().port(0).handle((request, response) -> {
            if (request.method().name().equals("POST")) {
                return response.status(201).header("X-Demo-Service", "test-backend")
                        .send(request.receive().retain());
            }
            return response.sendString(Mono.just(request.uri()));
        }).bindNow();
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("users", "/users", URI.create("http://localhost:" + backend.port()))),
                Duration.ofSeconds(3)).start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();

            var get = client.send(HttpRequest.newBuilder(URI.create(base + "/users/123?source=test")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, get.statusCode());
            assertEquals("/users/123?source=test", get.body());

            var post = client.send(HttpRequest.newBuilder(URI.create(base + "/users/echo"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("hello gateway")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, post.statusCode());
            assertEquals("hello gateway", post.body());
            assertEquals("test-backend", post.headers().firstValue("X-Demo-Service").orElseThrow());

            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            assertTrue(missing.body().contains("No matching route"));

            var extraPrefix = client.send(HttpRequest.newBuilder(URI.create(base + "/users-extra")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, extraPrefix.statusCode());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void reportsUpstreamTimeout() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.delay(Duration.ofSeconds(2)).map(value -> "late")))
                .bindNow();
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("users", "/users", URI.create("http://localhost:" + backend.port()))),
                Duration.ofMillis(200)).start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var result = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + "/users/1"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(504, result.statusCode());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void matchesRouteBeforeApplyingLocalLimitAndReturns429WithoutCallingBackend() throws Exception {
        AtomicInteger forwarded = new AtomicInteger();
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> {
                    forwarded.incrementAndGet();
                    return response.sendString(Mono.just("accepted"));
                }).bindNow();
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("users", "/users", URI.create("http://localhost:" + backend.port()))),
                Duration.ofSeconds(3),
                Map.of("users", new RateLimitPolicy(2, 1)),
                new LocalTokenBucketRateLimiter(() -> 0L)).start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/missing")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());

            for (int attempt = 0; attempt < 2; attempt++) {
                var accepted = client.send(HttpRequest.newBuilder(URI.create(base + "/users/1")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, accepted.statusCode());
            }
            var rejected = client.send(HttpRequest.newBuilder(URI.create(base + "/users/1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(429, rejected.statusCode());
            assertEquals(2, forwarded.get());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void reportsUpstreamUnavailableWhenConnectionFails() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok")))
                .bindNow();
        int deadPort = backend.port();
        backend.disposeNow();

        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("users", "/users", URI.create("http://localhost:" + deadPort))),
                Duration.ofSeconds(3)).start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + "/users/1"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(502, response.statusCode());
            assertTrue(response.body().contains("Upstream unavailable"));
        } finally {
            gateway.disposeNow();
        }
    }

    @Test
    void filtersHopByHopHeaders() throws Exception {
        AtomicBoolean receivedHopHeader = new AtomicBoolean(false);
        AtomicBoolean receivedCustomHeader = new AtomicBoolean(false);

        DisposableServer backend = HttpServer.create().port(0).handle((request, response) -> {
            if (request.requestHeaders().contains("X-Hop-Header")
                    || request.requestHeaders().contains("Keep-Alive")
                    || request.requestHeaders().contains("Proxy-Connection")) {
                receivedHopHeader.set(true);
            }
            if ("custom-value".equals(request.requestHeaders().get("X-Custom-Header"))) {
                receivedCustomHeader.set(true);
            }
            return response.header("X-Response-Custom", "backend-value")
                    .header("Keep-Alive", "timeout=5")
                    .header("Proxy-Authenticate", "Basic")
                    .sendString(Mono.just("filtered"));
        }).bindNow();

        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("users", "/users", URI.create("http://localhost:" + backend.port()))),
                Duration.ofSeconds(3)).start(0);
        try {
            var testClient = reactor.netty.http.client.HttpClient.create();
            String responseBody = testClient.headers(h -> h
                            .add("X-Custom-Header", "custom-value")
                            .add("Keep-Alive", "timeout=10")
                            .add("Proxy-Connection", "keep-alive")
                            .add("Connection", "close, X-Hop-Header")
                            .add("X-Hop-Header", "should-be-stripped"))
                    .get()
                    .uri("http://localhost:" + gateway.port() + "/users/test")
                    .responseSingle((resp, byteBufMono) -> {
                        assertEquals(200, resp.status().code());
                        assertEquals("backend-value", resp.responseHeaders().get("X-Response-Custom"));
                        assertFalse(resp.responseHeaders().contains("Keep-Alive"), "Keep-Alive should not reach client");
                        assertFalse(resp.responseHeaders().contains("Proxy-Authenticate"), "Proxy-Authenticate should not reach client");
                        return byteBufMono.asString();
                    })
                    .block(Duration.ofSeconds(3));

            assertEquals("filtered", responseBody);
            assertTrue(receivedCustomHeader.get(), "Custom header should reach backend");
            assertFalse(receivedHopHeader.get(), "Hop-by-hop headers should not reach backend");
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void distributesRequestsRoundRobinAcrossMultipleUpstreamsAndFailsOver() throws Exception {
        AtomicInteger server1Count = new AtomicInteger();
        AtomicInteger server2Count = new AtomicInteger();

        DisposableServer server1 = HttpServer.create().port(0).handle((request, response) -> {
            server1Count.incrementAndGet();
            return response.sendString(Mono.just("server1"));
        }).bindNow();

        DisposableServer server2 = HttpServer.create().port(0).handle((request, response) -> {
            server2Count.incrementAndGet();
            return response.sendString(Mono.just("server2"));
        }).bindNow();

        GatewayRoute route = new GatewayRoute("api", "/api", List.of(
                URI.create("http://localhost:" + server1.port()),
                URI.create("http://localhost:" + server2.port())
        ));

        DisposableServer gateway = new GatewayProxy(List.of(route), Duration.ofSeconds(3)).start(0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();

            for (int i = 0; i < 4; i++) {
                var res = client.send(HttpRequest.newBuilder(URI.create(base + "/api/test")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, res.statusCode());
            }
            assertEquals(2, server1Count.get());
            assertEquals(2, server2Count.get());

            server1.disposeNow();

            var failRes = client.send(HttpRequest.newBuilder(URI.create(base + "/api/test")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, failRes.statusCode());

            for (int i = 0; i < 3; i++) {
                var res = client.send(HttpRequest.newBuilder(URI.create(base + "/api/test")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, res.statusCode());
                assertEquals("server2", res.body());
            }
            assertEquals(5, server2Count.get());
        } finally {
            gateway.disposeNow();
            server2.disposeNow();
        }
    }

    @Test
    void includesRateLimitHeadersOnAllowedAndRejectedRequests() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok")))
                .bindNow();
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("vets", "/vets", URI.create("http://localhost:" + backend.port()))),
                Duration.ofSeconds(3),
                Map.of("vets", new RateLimitPolicy(2, 1.0)),
                new LocalTokenBucketRateLimiter(() -> 0L)).start(0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();

            var res1 = client.send(HttpRequest.newBuilder(URI.create(base + "/vets")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res1.statusCode());
            assertEquals("2", res1.headers().firstValue("X-RateLimit-Limit").orElseThrow());
            assertEquals("1", res1.headers().firstValue("X-RateLimit-Remaining").orElseThrow());

            var res2 = client.send(HttpRequest.newBuilder(URI.create(base + "/vets")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res2.statusCode());
            assertEquals("2", res2.headers().firstValue("X-RateLimit-Limit").orElseThrow());
            assertEquals("0", res2.headers().firstValue("X-RateLimit-Remaining").orElseThrow());

            var res3 = client.send(HttpRequest.newBuilder(URI.create(base + "/vets")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(429, res3.statusCode());
            assertEquals("2", res3.headers().firstValue("X-RateLimit-Limit").orElseThrow());
            assertEquals("0", res3.headers().firstValue("X-RateLimit-Remaining").orElseThrow());
            assertTrue(res3.headers().firstValue("Retry-After").isPresent());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void rateLimitsPerApiKeyWhenConfigured() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok")))
                .bindNow();
        GatewayRoute route = new GatewayRoute("customers", "/customers", URI.create("http://localhost:" + backend.port()));
        RateLimitPolicy policy = new RateLimitPolicy(1, 1.0, RateLimitPolicy.KeyType.API_KEY);

        DisposableServer gateway = new GatewayProxy(
                List.of(route),
                Duration.ofSeconds(3),
                Map.of("customers", policy),
                new LocalTokenBucketRateLimiter(() -> 0L),
                new LoadBalancer(),
                new ApiKeyVerifier(Map.of("customers", List.of(hash("key-a"), hash("key-b"))))).start(0);

        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();

            var resA1 = client.send(HttpRequest.newBuilder(URI.create(base + "/customers/1"))
                    .header("X-API-Key", "key-a").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resA1.statusCode());

            var resB1 = client.send(HttpRequest.newBuilder(URI.create(base + "/customers/2"))
                    .header("X-API-Key", "key-b").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resB1.statusCode());

            var resA2 = client.send(HttpRequest.newBuilder(URI.create(base + "/customers/1"))
                    .header("X-API-Key", "key-a").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(429, resA2.statusCode());

            var invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/customers/1"))
                    .header("X-API-Key", "unknown").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, invalid.statusCode());
            var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/customers/1"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, missing.statusCode());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void isolatesApiKeyBucketsByRoute() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok"))).bindNow();
        URI upstream = URI.create("http://localhost:" + backend.port());
        RateLimitPolicy policy = new RateLimitPolicy(1, 1, RateLimitPolicy.KeyType.API_KEY);
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("one", "/one", upstream), new GatewayRoute("two", "/two", upstream)),
                Duration.ofSeconds(3), Map.of("one", policy, "two", policy),
                new LocalTokenBucketRateLimiter(() -> 0L), new LoadBalancer(),
                new ApiKeyVerifier(Map.of("one", List.of(hash("same-key")),
                        "two", List.of(hash("same-key"))))).start(0);
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String base = "http://localhost:" + gateway.port();
            for (String path : List.of("/one", "/two")) {
                var result = client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("X-API-Key", "same-key").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
            }
            var limited = client.send(HttpRequest.newBuilder(URI.create(base + "/one"))
                    .header("X-API-Key", "same-key").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(429, limited.statusCode());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void clientIpBucketIgnoresSourcePortAndIsolatedByRoute() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok"))).bindNow();
        URI upstream = URI.create("http://localhost:" + backend.port());
        RateLimitPolicy policy = new RateLimitPolicy(1, 1, RateLimitPolicy.KeyType.CLIENT_IP);
        DisposableServer gateway = new GatewayProxy(
                List.of(new GatewayRoute("one", "/one", upstream), new GatewayRoute("two", "/two", upstream)),
                Duration.ofSeconds(3), Map.of("one", policy, "two", policy),
                new LocalTokenBucketRateLimiter(() -> 0L)).start(0);
        try {
            String base = "http://localhost:" + gateway.port();
            for (String path : List.of("/one", "/two")) {
                var result = java.net.http.HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
            }
            var limited = java.net.http.HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/one")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(429, limited.statusCode());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    @Test
    void failedUpstreamRecoversAfterCooldown() throws Exception {
        DisposableServer primary = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("primary"))).bindNow();
        int primaryPort = primary.port();
        DisposableServer secondary = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("secondary"))).bindNow();
        GatewayRoute route = new GatewayRoute("pool", "/pool", List.of(
                URI.create("http://localhost:" + primaryPort),
                URI.create("http://localhost:" + secondary.port())));
        LoadBalancer loadBalancer = new LoadBalancer(Duration.ofMillis(250), System::nanoTime);
        DisposableServer gateway = new GatewayProxy(List.of(route), Duration.ofSeconds(2),
                Map.of(), new LocalTokenBucketRateLimiter(), loadBalancer).start(0);
        DisposableServer recovered = null;
        try {
            primary.disposeNow();
            var client = java.net.http.HttpClient.newHttpClient();
            String url = "http://localhost:" + gateway.port() + "/pool";
            var failed = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(502, failed.statusCode());
            var healthy = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals("secondary", healthy.body());
            recovered = HttpServer.create().port(primaryPort)
                    .handle((request, response) -> response.sendString(Mono.just("recovered"))).bindNow();
            Thread.sleep(300);
            boolean reachedRecovered = false;
            for (int i = 0; i < 4; i++) {
                var result = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                reachedRecovered |= "recovered".equals(result.body());
            }
            assertTrue(reachedRecovered);
        } finally {
            gateway.disposeNow();
            if (recovered != null) {
                recovered.disposeNow();
            }
            secondary.disposeNow();
        }
    }

    @Test
    void forwardsLargeRequestAndResponseBody() throws Exception {
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.status(201)
                        .send(request.receive().retain())).bindNow();
        DisposableServer gateway = new GatewayProxy(List.of(new GatewayRoute("upload", "/upload",
                URI.create("http://localhost:" + backend.port()))), Duration.ofSeconds(5)).start(0);
        try {
            String body = "x".repeat(1_048_576);
            var result = java.net.http.HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + gateway.port() + "/upload?source=test"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, result.statusCode());
            assertEquals(body, result.body());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
        }
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
