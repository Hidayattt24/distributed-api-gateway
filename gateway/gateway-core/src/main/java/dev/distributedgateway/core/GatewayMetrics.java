package dev.distributedgateway.core;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class GatewayMetrics {
    private final PrometheusMeterRegistry registry;
    private final AtomicInteger inFlightRequests = new AtomicInteger(0);
    private final AtomicLong activeConfigVersion = new AtomicLong(0);

    private final ConcurrentHashMap<String, Counter> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> requestTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> upstreamTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> rateLimitedCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> upstreamFailureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> redisFailureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> upstreamHealthGauges = new ConcurrentHashMap<>();

    public GatewayMetrics() {
        this(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
    }

    public GatewayMetrics(PrometheusMeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("gateway_in_flight_requests", inFlightRequests, AtomicInteger::get)
                .description("Current number of in-flight requests being processed by the gateway")
                .register(registry);
        Gauge.builder("gateway_config_version", activeConfigVersion, AtomicLong::get)
                .description("Active configuration version deployed in the gateway")
                .register(registry);
    }

    public void requestStarted() {
        inFlightRequests.incrementAndGet();
    }

    public void requestFinished() {
        inFlightRequests.decrementAndGet();
    }

    public int inFlightCount() {
        return inFlightRequests.get();
    }

    public void setConfigVersion(long version) {
        activeConfigVersion.set(version);
    }

    public void recordRequest(String routeId, String method, int statusCode, Duration duration) {
        String key = routeId + ":" + method + ":" + statusCode;
        requestCounters.computeIfAbsent(key, k -> Counter.builder("gateway_requests_total")
                .tag("route", routeId)
                .tag("method", method)
                .tag("status", String.valueOf(statusCode))
                .description("Total number of requests processed by gateway")
                .register(registry)).increment();

        String timerKey = routeId + ":" + (statusCode / 100) + "xx";
        requestTimers.computeIfAbsent(timerKey, k -> Timer.builder("gateway_request_duration_seconds")
                .tag("route", routeId)
                .tag("status_class", (statusCode / 100) + "xx")
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Gateway request duration")
                .register(registry)).record(duration);
    }

    public void recordUpstreamDuration(String routeId, String upstream, Duration duration) {
        String key = routeId + ":" + upstream;
        upstreamTimers.computeIfAbsent(key, k -> Timer.builder("gateway_upstream_duration_seconds")
                .tag("route", routeId)
                .tag("upstream", upstream)
                .publishPercentiles(0.5, 0.95, 0.99)
                .description("Upstream request duration")
                .register(registry)).record(duration);
    }

    public void recordRateLimited(String routeId, String keyType) {
        String key = routeId + ":" + keyType;
        rateLimitedCounters.computeIfAbsent(key, k -> Counter.builder("gateway_rate_limited_total")
                .tag("route", routeId)
                .tag("key_type", keyType)
                .description("Total rate limited requests")
                .register(registry)).increment();
    }

    public void recordUpstreamFailure(String routeId, String upstream, String reason) {
        String key = routeId + ":" + upstream + ":" + reason;
        upstreamFailureCounters.computeIfAbsent(key, k -> Counter.builder("gateway_upstream_failures_total")
                .tag("route", routeId)
                .tag("upstream", upstream)
                .tag("reason", reason)
                .description("Total upstream failures")
                .register(registry)).increment();
    }

    public void recordRedisFailure(String failureMode, String reason) {
        String key = failureMode + ":" + reason;
        redisFailureCounters.computeIfAbsent(key, k -> Counter.builder("gateway_redis_failures_total")
                .tag("failure_mode", failureMode)
                .tag("reason", reason)
                .description("Redis rate limiter failures by fallback mode")
                .register(registry)).increment();
    }

    public void setUpstreamHealth(String routeId, String upstream, boolean healthy) {
        String key = routeId + ":" + upstream;
        AtomicInteger gaugeValue = upstreamHealthGauges.computeIfAbsent(key, k -> {
            AtomicInteger val = new AtomicInteger(1);
            Gauge.builder("gateway_upstream_health", val, AtomicInteger::get)
                    .tag("route", routeId)
                    .tag("upstream", upstream)
                    .description("Upstream health status (1=healthy, 0=unhealthy/cooldown)")
                    .register(registry);
            return val;
        });
        gaugeValue.set(healthy ? 1 : 0);
    }

    public String scrape() {
        return registry.scrape();
    }

    public PrometheusMeterRegistry registry() {
        return registry;
    }
}
