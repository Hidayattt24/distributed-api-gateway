package dev.distributedgateway.core;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record GatewayRoute(String id, String pathPrefix, List<URI> upstreams) {
    public GatewayRoute(String id, String pathPrefix, URI upstream) {
        this(id, pathPrefix, List.of(Objects.requireNonNull(upstream, "upstream required")));
    }

    public GatewayRoute {
        Objects.requireNonNull(id, "route id required");
        Objects.requireNonNull(pathPrefix, "pathPrefix required");
        Objects.requireNonNull(upstreams, "upstreams required");
        if (id.isBlank() || !pathPrefix.startsWith("/") || (pathPrefix.endsWith("/") && pathPrefix.length() > 1)) {
            throw new IllegalArgumentException("Invalid route id or path prefix");
        }
        if (upstreams.isEmpty()) {
            throw new IllegalArgumentException("Route must have at least one upstream");
        }
        for (URI upstream : upstreams) {
            if (upstream == null || !("http".equalsIgnoreCase(upstream.getScheme())
                    || "https".equalsIgnoreCase(upstream.getScheme())) || upstream.getHost() == null) {
                throw new IllegalArgumentException("Upstream must be an absolute HTTP(S) URL");
            }
        }
        upstreams = List.copyOf(upstreams);
    }

    public URI upstream() {
        return upstreams.getFirst();
    }

    public boolean matches(String requestUri) {
        String path = requestUri.split("\\?", 2)[0];
        return path.equals(pathPrefix) || path.startsWith(pathPrefix + "/");
    }
}
