package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class UpstreamAllowlistTest {
    @Test
    void permitsOnlyExactConfiguredOrigins() {
        UpstreamAllowlist allowlist = UpstreamAllowlist.fromCsv("http://service.internal:8080", true);
        assertDoesNotThrow(() -> allowlist.validate(config("http://service.internal:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> allowlist.validate(config("http://service.internal:9090")));
        assertThrows(IllegalArgumentException.class,
                () -> allowlist.validate(config("http://metadata.internal:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamAllowlist.fromCsv("", true));
    }

    private static GatewayConfig config(String upstream) {
        return new GatewayConfig(1, 5, List.of(
                new GatewayConfig.RouteDefinition("demo", "/demo", List.of(upstream), null)));
    }
}
