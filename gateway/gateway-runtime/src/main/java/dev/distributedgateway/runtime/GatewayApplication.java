package dev.distributedgateway.runtime;

import dev.distributedgateway.core.ApiKeyVerifier;
import dev.distributedgateway.core.GatewayProxy;
import dev.distributedgateway.core.GatewayRoute;
import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.LoadBalancer;
import dev.distributedgateway.core.RateLimitPolicy;
import dev.distributedgateway.core.RateLimiter;
import dev.distributedgateway.ratelimit.RedisRateLimiter;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
        Map<String, List<String>> apiKeyHashes = new HashMap<>();
        for (String entry : properties.getProperty("routes", "").split(",")) {
            String id = entry.trim();
            if (id.isEmpty()) {
                continue;
            }
            String prefix = required(properties, "route." + id + ".prefix");
            String upstreamConfig = System.getenv().getOrDefault(id.toUpperCase().replace('-', '_') + "_SERVICE_URL",
                    required(properties, "route." + id + ".upstream"));
            List<URI> upstreams = Arrays.stream(upstreamConfig.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(URI::create)
                    .toList();
            routes.add(new GatewayRoute(id, prefix, upstreams));
            if (Boolean.parseBoolean(properties.getProperty("rateLimit." + id + ".enabled", "false"))) {
                int capacity = Integer.parseInt(required(properties, "rateLimit." + id + ".capacity"));
                double refill = Double.parseDouble(required(properties, "rateLimit." + id + ".refillTokensPerSecond"));
                String keyTypeStr = properties.getProperty("rateLimit." + id + ".keyType", "ROUTE");
                String failureModeStr = properties.getProperty("rateLimit." + id + ".failureMode", "LOCAL_FALLBACK");
                RateLimitPolicy.KeyType keyType = RateLimitPolicy.KeyType.valueOf(keyTypeStr.trim().toUpperCase());
                RateLimitPolicy.RedisFailureMode failureMode = RateLimitPolicy.RedisFailureMode.valueOf(failureModeStr.trim().toUpperCase());
                policies.put(id, new RateLimitPolicy(capacity, refill, keyType, failureMode));
                if (keyType == RateLimitPolicy.KeyType.API_KEY) {
                    String envName = "RATE_LIMIT_" + id.toUpperCase().replace('-', '_') + "_API_KEY_SHA256";
                    String configured = System.getenv().getOrDefault(envName, "");
                    List<String> hashes = Arrays.stream(configured.split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).toList();
                    if (hashes.isEmpty()) {
                        throw new IllegalArgumentException("Missing API key hashes in environment variable " + envName);
                    }
                    apiKeyHashes.put(id, hashes);
                }
            }
        }
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one route in gateway.properties");
        }

        String redisUrl = System.getenv().getOrDefault("REDIS_URL", properties.getProperty("redis.url", ""));
        RateLimiter rateLimiter;
        if (!redisUrl.isBlank()) {
            RedisRateLimiter redisLimiter = new RedisRateLimiter(redisUrl);
            Runtime.getRuntime().addShutdownHook(new Thread(redisLimiter::close));
            rateLimiter = redisLimiter;
        } else {
            rateLimiter = new LocalTokenBucketRateLimiter();
        }

        DisposableServer server = new GatewayProxy(routes, timeout,
                policies, rateLimiter, new LoadBalancer(), new ApiKeyVerifier(apiKeyHashes)).start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::disposeNow));
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
