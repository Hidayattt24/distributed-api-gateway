package dev.distributedgateway.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.netty.http.server.HttpServerRequest;

/** Trusts a single overwritten X-Real-IP value only from configured direct proxy peers. */
public final class ClientIdentityResolver {
    private final Set<String> trustedPeers;
    private final boolean requireTrustedProxy;

    private ClientIdentityResolver(Set<String> trustedPeers, boolean requireTrustedProxy) {
        this.trustedPeers = trustedPeers;
        this.requireTrustedProxy = requireTrustedProxy;
        if (requireTrustedProxy && trustedPeers.isEmpty()) {
            throw new IllegalArgumentException("GATEWAY_TRUSTED_PROXY_IPS is required");
        }
    }

    public static ClientIdentityResolver direct() {
        return new ClientIdentityResolver(Set.of(), false);
    }

    public static ClientIdentityResolver fromCsv(String csv, boolean requireTrustedProxy) {
        Set<String> peers = Arrays.stream((csv == null ? "" : csv).split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .map(value -> parseLiteral(value).orElseThrow(
                        () -> new IllegalArgumentException("Invalid trusted proxy IP: " + value)))
                .collect(Collectors.toUnmodifiableSet());
        return new ClientIdentityResolver(peers, requireTrustedProxy);
    }

    public Optional<String> resolve(HttpServerRequest request) {
        SocketAddress remote = request.remoteAddress();
        if (!(remote instanceof InetSocketAddress peer) || peer.getAddress() == null) {
            return Optional.empty();
        }
        String peerIp = peer.getAddress().getHostAddress();
        if (trustedPeers.contains(peerIp)) {
            return parseLiteral(request.requestHeaders().get("X-Real-IP"));
        }
        return requireTrustedProxy ? Optional.empty() : Optional.of(peerIp);
    }

    private static Optional<String> parseLiteral(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(value).getHostAddress());
        } catch (UnknownHostException error) {
            return Optional.empty();
        }
    }
}
