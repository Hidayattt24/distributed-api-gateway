package dev.distributedgateway.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Verifies API keys against configured SHA-256 digests without storing plaintext keys. */
public final class ApiKeyVerifier {
    private final Map<String, List<byte[]>> hashesByRoute;

    public ApiKeyVerifier(Map<String, List<String>> hexHashesByRoute) {
        Objects.requireNonNull(hexHashesByRoute);
        Map<String, List<byte[]>> parsed = new HashMap<>();
        hexHashesByRoute.forEach((route, hashes) -> {
            Objects.requireNonNull(route);
            parsed.put(route, hashes.stream().map(hash -> {
                if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
                    throw new IllegalArgumentException("API key SHA-256 hash must be 64 hexadecimal characters");
                }
                return HexFormat.of().parseHex(hash);
            }).toList());
        });
        this.hashesByRoute = Map.copyOf(parsed);
    }

    public Optional<String> verify(String routeId, String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        byte[] digest = sha256(presentedKey);
        boolean matched = false;
        for (byte[] configured : hashesByRoute.getOrDefault(routeId, List.of())) {
            matched |= MessageDigest.isEqual(configured, digest);
        }
        return matched ? Optional.of(HexFormat.of().formatHex(digest)) : Optional.empty();
    }

    public boolean hasKeys(String routeId) {
        return !hashesByRoute.getOrDefault(routeId, List.of()).isEmpty();
    }

    private static byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
