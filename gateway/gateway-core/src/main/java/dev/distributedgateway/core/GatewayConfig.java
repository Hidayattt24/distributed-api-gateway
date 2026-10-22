package dev.distributedgateway.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Complete, versioned gateway configuration shared by the data and control planes. */
public record GatewayConfig(long version, long responseTimeoutSeconds, List<RouteDefinition> routes) {
    public record RouteDefinition(String id, String pathPrefix, List<String> upstreams, PolicyDefinition policy) {
        public RouteDefinition {
            if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                throw new IllegalArgumentException("Route id must contain letters, digits, _ or -");
            }
            Objects.requireNonNull(pathPrefix, "pathPrefix required");
            if (pathPrefix.equals("/_gateway") || pathPrefix.startsWith("/_gateway/")) {
                throw new IllegalArgumentException("/_gateway is reserved for gateway status");
            }
            Objects.requireNonNull(upstreams, "upstreams required");
            if (upstreams.isEmpty()) {
                throw new IllegalArgumentException("Route " + id + " needs an upstream");
            }
            upstreams = List.copyOf(upstreams);
            for (String address : upstreams) {
                URI uri = URI.create(address);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                        || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                    throw new IllegalArgumentException("Upstream must be an HTTP origin: " + address);
                }
            }
            new GatewayRoute(id, pathPrefix, URI.create(upstreams.getFirst()));
        }
    }

    public record PolicyDefinition(int capacity, double refillTokensPerSecond,
            RateLimitPolicy.KeyType keyType, RateLimitPolicy.RedisFailureMode failureMode,
            List<String> apiKeySha256) {
        public PolicyDefinition {
            keyType = keyType == null ? RateLimitPolicy.KeyType.ROUTE : keyType;
            failureMode = failureMode == null ? RateLimitPolicy.RedisFailureMode.LOCAL_FALLBACK : failureMode;
            apiKeySha256 = apiKeySha256 == null ? List.of() : List.copyOf(apiKeySha256);
            new RateLimitPolicy(capacity, refillTokensPerSecond, keyType, failureMode);
            if (keyType == RateLimitPolicy.KeyType.API_KEY) {
                if (apiKeySha256.isEmpty()) {
                    throw new IllegalArgumentException("API_KEY policy requires SHA-256 hashes");
                }
                new ApiKeyVerifier(Map.of("route", apiKeySha256));
            }
        }
    }

    public GatewayConfig {
        if (version < 1 || responseTimeoutSeconds < 1 || responseTimeoutSeconds > 300) {
            throw new IllegalArgumentException("Version must be positive and timeout must be 1..300 seconds");
        }
        Objects.requireNonNull(routes, "routes required");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("At least one route is required");
        }
        routes = List.copyOf(routes);
        Set<String> ids = new HashSet<>();
        for (RouteDefinition route : routes) {
            if (!ids.add(route.id())) {
                throw new IllegalArgumentException("Duplicate route id: " + route.id());
            }
        }
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                String a = routes.get(i).pathPrefix();
                String b = routes.get(j).pathPrefix();
                if (a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/")) {
                    throw new IllegalArgumentException("Overlapping route prefixes: " + a + " and " + b);
                }
            }
        }
    }

    public List<GatewayRoute> compiledRoutes() {
        return routes.stream().map(route -> new GatewayRoute(route.id(), route.pathPrefix(),
                route.upstreams().stream().map(URI::create).toList())).toList();
    }

    public Map<String, RateLimitPolicy> compiledPolicies() {
        Map<String, RateLimitPolicy> policies = new HashMap<>();
        for (RouteDefinition route : routes) {
            PolicyDefinition policy = route.policy();
            if (policy != null) {
                policies.put(route.id(), new RateLimitPolicy(policy.capacity(), policy.refillTokensPerSecond(),
                        policy.keyType(), policy.failureMode()));
            }
        }
        return Map.copyOf(policies);
    }

    public ApiKeyVerifier apiKeyVerifier() {
        Map<String, List<String>> hashes = new HashMap<>();
        for (RouteDefinition route : routes) {
            if (route.policy() != null && route.policy().keyType() == RateLimitPolicy.KeyType.API_KEY) {
                hashes.put(route.id(), route.policy().apiKeySha256());
            }
        }
        return new ApiKeyVerifier(hashes);
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("snapshot.version", Long.toString(version));
        properties.setProperty("gateway.response-timeout-seconds", Long.toString(responseTimeoutSeconds));
        properties.setProperty("routes", String.join(",", routes.stream().map(RouteDefinition::id).toList()));
        for (RouteDefinition route : routes) {
            String prefix = "route." + route.id() + ".";
            properties.setProperty(prefix + "prefix", route.pathPrefix());
            properties.setProperty(prefix + "upstream", String.join(",", route.upstreams()));
            PolicyDefinition policy = route.policy();
            if (policy != null) {
                String key = "rateLimit." + route.id() + ".";
                properties.setProperty(key + "enabled", "true");
                properties.setProperty(key + "capacity", Integer.toString(policy.capacity()));
                properties.setProperty(key + "refillTokensPerSecond", Double.toString(policy.refillTokensPerSecond()));
                properties.setProperty(key + "keyType", policy.keyType().name());
                properties.setProperty(key + "failureMode", policy.failureMode().name());
                properties.setProperty(key + "apiKeySha256", String.join(",", policy.apiKeySha256()));
            }
        }
        return properties;
    }

    public static GatewayConfig fromProperties(Properties properties, Map<String, String> environment, long version) {
        long timeout = Long.parseLong(required(properties, "gateway.response-timeout-seconds"));
        List<RouteDefinition> definitions = new ArrayList<>();
        for (String entry : required(properties, "routes").split(",")) {
            String id = entry.trim();
            if (id.isEmpty()) {
                continue;
            }
            String upstreams = environment.getOrDefault(id.toUpperCase().replace('-', '_') + "_SERVICE_URL",
                    required(properties, "route." + id + ".upstream"));
            List<String> addresses = Arrays.stream(upstreams.split(",")).map(String::trim)
                    .filter(value -> !value.isEmpty()).toList();
            PolicyDefinition policy = null;
            String key = "rateLimit." + id + ".";
            if (Boolean.parseBoolean(properties.getProperty(key + "enabled", "false"))) {
                RateLimitPolicy.KeyType type = RateLimitPolicy.KeyType.valueOf(
                        properties.getProperty(key + "keyType", "ROUTE").trim().toUpperCase());
                String hashes = environment.getOrDefault("RATE_LIMIT_" + id.toUpperCase().replace('-', '_')
                        + "_API_KEY_SHA256", properties.getProperty(key + "apiKeySha256", ""));
                policy = new PolicyDefinition(Integer.parseInt(required(properties, key + "capacity")),
                        Double.parseDouble(required(properties, key + "refillTokensPerSecond")), type,
                        RateLimitPolicy.RedisFailureMode.valueOf(properties.getProperty(key + "failureMode",
                                "LOCAL_FALLBACK").trim().toUpperCase()),
                        Arrays.stream(hashes.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList());
            }
            definitions.add(new RouteDefinition(id, required(properties, "route." + id + ".prefix"),
                    addresses, policy));
        }
        return new GatewayConfig(version, timeout, definitions);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value;
    }
}
