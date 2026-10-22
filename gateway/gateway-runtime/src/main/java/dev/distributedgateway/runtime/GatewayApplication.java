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
import java.util.ArrayList;
import java.util.HashMap;
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
        Duration timeout = Duration.ofSeconds(Long.parseLong(properties.getProperty("gateway.response-timeout-seconds")));
        List<GatewayRoute> routes = new ArrayList<>();
        Map<String, RateLimitPolicy> policies = new HashMap<>();
        for (String entry : properties.getProperty("routes", "").split(",")) {
            String id = entry.trim();
            if (id.isEmpty()) {
                continue;
            }
            String prefix = required(properties, "route." + id + ".prefix");
            String upstream = System.getenv().getOrDefault(id.toUpperCase().replace('-', '_') + "_SERVICE_URL",
                    required(properties, "route." + id + ".upstream"));
            routes.add(new GatewayRoute(id, prefix, URI.create(upstream)));
            if (Boolean.parseBoolean(properties.getProperty("rateLimit." + id + ".enabled", "false"))) {
                policies.put(id, new RateLimitPolicy(
                        Integer.parseInt(required(properties, "rateLimit." + id + ".capacity")),
                        Double.parseDouble(required(properties, "rateLimit." + id + ".refillTokensPerSecond"))));
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one route in gateway.properties");
        }
        DisposableServer server = new GatewayProxy(routes, timeout,
                policies, new LocalTokenBucketRateLimiter()).start(port);
        System.out.printf("Gateway listening on http://localhost:%d; routes: %s%n", server.port(), routes);
        server.onDispose().block();
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value;
    }
}
