package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
}
