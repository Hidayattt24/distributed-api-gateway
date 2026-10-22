package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {
    @Test
    void recordsRequestsAndExportsPrometheusTextFormat() {
        GatewayMetrics metrics = new GatewayMetrics();
        metrics.setConfigVersion(3);

        metrics.requestStarted();
        assertEquals(1, metrics.inFlightCount());

        metrics.recordRequest("vets", "GET", 200, Duration.ofMillis(25));
        metrics.recordRequest("vets", "GET", 429, Duration.ofMillis(2));
        metrics.recordUpstreamDuration("vets", "http://vets:8083", Duration.ofMillis(20));
        metrics.recordRateLimited("vets", "ROUTE");
        metrics.recordUpstreamFailure("vets", "http://vets:8083", "timeout");
        metrics.setUpstreamHealth("vets", "http://vets:8083", true);

        metrics.requestFinished();
        assertEquals(0, metrics.inFlightCount());

        String scrape = metrics.scrape();
        assertTrue(scrape.contains("gateway_requests_total"));
        assertTrue(scrape.contains("route=\"vets\""));
        assertTrue(scrape.contains("status=\"200\""));
        assertTrue(scrape.contains("status=\"429\""));
        assertTrue(scrape.contains("gateway_request_duration_seconds"));
        assertTrue(scrape.contains("gateway_upstream_duration_seconds"));
        assertTrue(scrape.contains("gateway_rate_limited_total"));
        assertTrue(scrape.contains("gateway_upstream_failures_total"));
        assertTrue(scrape.contains("gateway_upstream_health"));
        assertTrue(scrape.contains("gateway_config_version"));
        assertTrue(scrape.contains("gateway_in_flight_requests"));
    }
}
