package dev.distributedgateway.core;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Operator-owned list of exact upstream origins; never part of a published snapshot. */
public final class UpstreamAllowlist {
    private final Set<String> origins;
    private final boolean required;

    private UpstreamAllowlist(Set<String> origins, boolean required) {
        this.origins = origins;
        this.required = required;
        if (required && origins.isEmpty()) {
            throw new IllegalArgumentException("GATEWAY_ALLOWED_UPSTREAMS is required");
        }
    }

    public static UpstreamAllowlist fromCsv(String csv, boolean required) {
        Set<String> origins = Arrays.stream((csv == null ? "" : csv).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> normalize(URI.create(value)))
                .collect(Collectors.toUnmodifiableSet());
        return new UpstreamAllowlist(origins, required);
    }

    public static UpstreamAllowlist allowAllForLocalUse() {
        return new UpstreamAllowlist(Set.of(), false);
    }

    public void validate(GatewayConfig config) {
        if (origins.isEmpty() && !required) {
            return;
        }
        for (GatewayConfig.RouteDefinition route : config.routes()) {
            for (String upstream : route.upstreams()) {
                if (!origins.contains(normalize(URI.create(upstream)))) {
                    throw new IllegalArgumentException("Upstream not allowed for route " + route.id() + ": " + upstream);
                }
            }
        }
    }

    private static String normalize(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (!(scheme.equals("http") || scheme.equals("https")) || host.isEmpty() || port < 1 || port > 65535
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("Expected an HTTP(S) origin with explicit port: " + uri);
        }
        return scheme + "://" + host + ":" + port;
    }
}
