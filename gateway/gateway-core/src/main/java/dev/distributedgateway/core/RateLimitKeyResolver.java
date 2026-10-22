package dev.distributedgateway.core;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import reactor.netty.http.server.HttpServerRequest;

public final class RateLimitKeyResolver {
    private RateLimitKeyResolver() {
    }

    public static Optional<String> resolveKey(HttpServerRequest request, GatewayRoute route,
            RateLimitPolicy policy, ApiKeyVerifier apiKeyVerifier) {
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
            case CLIENT_IP -> {
                SocketAddress remote = request.remoteAddress();
                String ip = remote instanceof InetSocketAddress inet
                        ? (inet.getAddress() != null ? inet.getAddress().getHostAddress() : inet.getHostString())
                        : "unknown";
                yield Optional.of(prefix + "ip:" + ip);
            }
        };
    }
}
