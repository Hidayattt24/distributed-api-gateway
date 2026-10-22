package dev.distributedgateway.runtime;

import dev.distributedgateway.core.GatewayProxy;
import dev.distributedgateway.core.GatewayRoute;
import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.RateLimitPolicy;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import reactor.netty.DisposableServer;

public final class GatewayApplication {
    private GatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path configuration = Path.of(System.getenv().getOrDefault("GATEWAY_CONFIG", "config/gateway.properties"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configuration)) {
            properties.load(input);
        }

        int port = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT", properties.getProperty("gateway.port")));
        String upstream = System.getenv().getOrDefault("USER_SERVICE_URL", properties.getProperty("route.users.upstream"));
        GatewayRoute route = new GatewayRoute("users", properties.getProperty("route.users.prefix"), URI.create(upstream));
        Duration timeout = Duration.ofSeconds(Long.parseLong(properties.getProperty("gateway.response-timeout-seconds")));
        boolean localLimitEnabled = Boolean.parseBoolean(properties.getProperty("rateLimit.users.enabled", "false"));
        Map<String, RateLimitPolicy> policies = localLimitEnabled
                ? Map.of(route.id(), new RateLimitPolicy(
                        Integer.parseInt(properties.getProperty("rateLimit.users.capacity")),
                        Double.parseDouble(properties.getProperty("rateLimit.users.refillTokensPerSecond"))))
                : Map.of();
        DisposableServer server = new GatewayProxy(List.of(route), timeout,
                policies, new LocalTokenBucketRateLimiter()).start(port);
        System.out.printf("Gateway listening on http://localhost:%d; /users -> %s; local rate limit: %s%n",
                server.port(), upstream, localLimitEnabled ? "enabled" : "disabled");
        server.onDispose().block();
    }
}
