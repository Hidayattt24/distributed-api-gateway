package dev.distributedgateway.core;

import java.net.URI;
import java.util.Objects;

public record GatewayRoute(String id, String pathPrefix, URI upstream) {
    public GatewayRoute {
        Objects.requireNonNull(id);
        Objects.requireNonNull(pathPrefix);
        Objects.requireNonNull(upstream);
        if (id.isBlank() || !pathPrefix.startsWith("/") || pathPrefix.endsWith("/") && pathPrefix.length() > 1) {
            throw new IllegalArgumentException("Invalid route id or path prefix");
        }
        if (!"http".equalsIgnoreCase(upstream.getScheme()) || upstream.getHost() == null) {
            throw new IllegalArgumentException("MVP upstream must be an absolute HTTP URL");
        }
    }

    public boolean matches(String requestUri) {
        String path = requestUri.split("\\?", 2)[0];
        return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
    }
}
