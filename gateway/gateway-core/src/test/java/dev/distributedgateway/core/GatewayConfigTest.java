package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class GatewayConfigTest {
    @Test
    void rejectsOverlappingPrefixesAndRoundTripsSnapshot() {
        var one = new GatewayConfig.RouteDefinition("one", "/one", List.of("http://localhost:8081"),
                new GatewayConfig.PolicyDefinition(3, 1, RateLimitPolicy.KeyType.ROUTE,
                        RateLimitPolicy.RedisFailureMode.LOCAL_FALLBACK, List.of()));
        GatewayConfig config = new GatewayConfig(5, 7, List.of(one));
        GatewayConfig copy = GatewayConfig.fromProperties(config.toProperties(), Map.of(), 5);
        assertEquals(config, copy);
        var overlap = new GatewayConfig.RouteDefinition("nested", "/one/child",
                List.of("http://localhost:8082"), null);
        assertThrows(IllegalArgumentException.class, () -> new GatewayConfig(6, 7, List.of(one, overlap)));
    }

    @Test
    void appliesNewVersionWithoutRestart() throws Exception {
        DisposableServer upstream = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("upstream"))).bindNow();
        String address = "http://localhost:" + upstream.port();
        GatewayConfig first = new GatewayConfig(1, 3, List.of(
                new GatewayConfig.RouteDefinition("first", "/first", List.of(address), null)));
        GatewayProxy proxy = new GatewayProxy(first, new LocalTokenBucketRateLimiter());
        DisposableServer server = proxy.start(0);
        DisposableServer management = proxy.startManagement("127.0.0.1", 0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();
            assertEquals(200, status(client, base + "/first"));
            GatewayConfig second = new GatewayConfig(2, 3, List.of(
                    new GatewayConfig.RouteDefinition("second", "/second", List.of(address), null)));
            proxy.install(second);
            assertEquals(404, status(client, base + "/first"));
            assertEquals(200, status(client, base + "/second"));
            assertEquals(404, status(client, base + "/_gateway/version"));
            assertEquals("2", client.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                            + management.port() + "/_gateway/version"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertThrows(IllegalArgumentException.class, () -> proxy.install(first));
        } finally {
            management.disposeNow();
            server.disposeNow();
            upstream.disposeNow();
        }
    }

    private static int status(HttpClient client, String address) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(3))
                .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
