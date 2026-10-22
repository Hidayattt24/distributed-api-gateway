package dev.distributedgateway.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.distributedgateway.core.GatewayProxy;
import dev.distributedgateway.core.GatewayRoute;
import dev.distributedgateway.core.RateLimitPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Runs only when REDIS_TEST_URL points to a real, disposable Redis instance. */
class RedisIntegrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_TEST_URL", matches = ".+")
    void twoGatewaysShareOneAtomicQuota() {
        String redisUrl = System.getenv("REDIS_TEST_URL");
        String routeId = "integration-" + UUID.randomUUID();
        DisposableServer backend = HttpServer.create().port(0)
                .handle((request, response) -> response.sendString(Mono.just("ok"))).bindNow();
        GatewayRoute route = new GatewayRoute(routeId, "/quota", URI.create("http://localhost:" + backend.port()));
        RateLimitPolicy policy = new RateLimitPolicy(10, 0.000001);
        try (RedisRateLimiter limiterA = new RedisRateLimiter(redisUrl);
                RedisRateLimiter limiterB = new RedisRateLimiter(redisUrl)) {
            DisposableServer gatewayA = new GatewayProxy(List.of(route), Duration.ofSeconds(5),
                    Map.of(routeId, policy), limiterA).start(0);
            DisposableServer gatewayB = new GatewayProxy(List.of(route), Duration.ofSeconds(5),
                    Map.of(routeId, policy), limiterB).start(0);
            try {
                HttpClient client = HttpClient.newHttpClient();
                @SuppressWarnings("unchecked")
                CompletableFuture<HttpResponse<String>>[] requests = new CompletableFuture[30];
                for (int i = 0; i < requests.length; i++) {
                    int port = i % 2 == 0 ? gatewayA.port() : gatewayB.port();
                    requests[i] = client.sendAsync(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/quota")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                }
                CompletableFuture.allOf(requests).join();
                long accepted = java.util.Arrays.stream(requests).map(CompletableFuture::join)
                        .filter(response -> response.statusCode() == 200).count();
                long rejected = java.util.Arrays.stream(requests).map(CompletableFuture::join)
                        .filter(response -> response.statusCode() == 429).count();
                assertEquals(10, accepted);
                assertEquals(20, rejected);
            } finally {
                gatewayA.disposeNow();
                gatewayB.disposeNow();
            }
        } finally {
            backend.disposeNow();
        }
    }
}
