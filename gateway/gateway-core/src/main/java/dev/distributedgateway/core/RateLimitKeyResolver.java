package dev.distributedgateway.core;

import java.util.Objects;
import java.util.Optional;
import reactor.netty.http.server.HttpServerRequest;

public final class RateLimitKeyResolver {
    private RateLimitKeyResolver() {
    }

    public static Optional<String> resolveKey(HttpServerRequest request, GatewayRoute route,
            RateLimitPolicy policy, ApiKeyVerifier apiKeyVerifier) {
        return resolveKey(request, route, policy, apiKeyVerifier,
                ClientIdentityResolver.direct().resolve(request).orElse("unknown"));
    }

    public static Optional<String> resolveKey(HttpServerRequest request, GatewayRoute route,
            RateLimitPolicy policy, ApiKeyVerifier apiKeyVerifier, String clientIp) {
        Objects.requireNonNull(request, "request required");
        Objects.requireNonNull(route, "route required");
        Objects.requireNonNull(policy, "policy required");

        Objects.requireNonNull(apiKeyVerifier, "apiKeyVerifier required");
        String prefix = route.id().length() + ":" + route.id() + ":";
        return switch (policy.keyType()) {
            case ROUTE -> Optional.of(prefix + "route");
            case API_KEY -> {
                String apiKey = request.requestHeaders().get("X-API-Key");
                yield apiKeyVerifier.verify(route.id(), apiKey).map(hash -> prefix + "apikey:" + hash);
            }
            case CLIENT_IP -> Optional.of(prefix + "ip:" + clientIp);
        };
    }
}
