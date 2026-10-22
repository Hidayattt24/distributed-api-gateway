package dev.distributedgateway.core;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class LoadBalancer {
    private final Duration cooldownDuration;
    private final LongSupplier nanoTimeSupplier;
    private final Map<String, AtomicInteger> routeCounters = new ConcurrentHashMap<>();
    private final Map<URI, Long> unhealthyUntil = new ConcurrentHashMap<>();

    public LoadBalancer() {
        this(Duration.ofSeconds(10), System::nanoTime);
    }

    public LoadBalancer(Duration cooldownDuration, LongSupplier nanoTimeSupplier) {
        this.cooldownDuration = Objects.requireNonNull(cooldownDuration);
        this.nanoTimeSupplier = Objects.requireNonNull(nanoTimeSupplier);
    }

    public URI select(GatewayRoute route) {
        List<URI> upstreams = route.upstreams();
        if (upstreams.size() == 1) {
            URI single = upstreams.getFirst();
            return isHealthy(single) ? single : null;
        }

        AtomicInteger counter = routeCounters.computeIfAbsent(route.id(), k -> new AtomicInteger());
        int size = upstreams.size();
        int startIndex = Math.floorMod(counter.getAndIncrement(), size);

        for (int i = 0; i < size; i++) {
            URI candidate = upstreams.get((startIndex + i) % size);
            if (isHealthy(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public void reportFailure(URI upstream) {
        long cooldownNanos = cooldownDuration.toNanos();
        unhealthyUntil.put(upstream, nanoTimeSupplier.getAsLong() + cooldownNanos);
    }

    public void reportSuccess(URI upstream) {
        unhealthyUntil.remove(upstream);
    }

    public boolean isHealthy(URI upstream) {
        Long until = unhealthyUntil.get(upstream);
        if (until == null) {
            return true;
        }
        if (nanoTimeSupplier.getAsLong() >= until) {
            unhealthyUntil.remove(upstream);
            return true;
        }
        return false;
    }
}
