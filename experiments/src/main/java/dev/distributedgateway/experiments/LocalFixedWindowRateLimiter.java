package dev.distributedgateway.experiments;

import dev.distributedgateway.core.RateLimitDecision;
import dev.distributedgateway.core.RateLimitPolicy;
import dev.distributedgateway.core.RateLimiter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;

/** Experimental local baseline. A boundary can admit twice the configured capacity. */
public final class LocalFixedWindowRateLimiter implements RateLimiter {
    private final Map<String, Window> windows = new HashMap<>();
    private final LongSupplier nanoTime;
    private final int maxKeys;
    private final long idleNanos;

    public LocalFixedWindowRateLimiter() {
        this(System::nanoTime, 10_000, Duration.ofMinutes(2).toNanos());
    }

    public LocalFixedWindowRateLimiter(LongSupplier nanoTime, int maxKeys, long idleNanos) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (maxKeys < 1 || idleNanos < 1) {
            throw new IllegalArgumentException("maxKeys and idleNanos must be positive");
        }
        this.maxKeys = maxKeys;
        this.idleNanos = idleNanos;
    }

    @Override
    public Mono<RateLimitDecision> evaluate(String key, RateLimitPolicy policy) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(policy);
        return Mono.fromSupplier(() -> decide(key, policy));
    }

    private synchronized RateLimitDecision decide(String key, RateLimitPolicy policy) {
        long now = nanoTime.getAsLong();
        long windowNanos = Math.max(1L, (long) Math.ceil(
                policy.capacity() / policy.refillTokensPerSecond() * 1_000_000_000.0));
        if (!windows.containsKey(key) && windows.size() >= maxKeys) {
            windows.entrySet().removeIf(entry -> Math.max(0, now - entry.getValue().lastSeen) > idleNanos);
            if (windows.size() >= maxKeys) {
                return RateLimitDecision.rejected(policy.capacity(),
                        Math.max(1L, (long) Math.ceil(idleNanos / 1_000_000_000.0)));
            }
        }
        long index = Math.floorDiv(now, windowNanos);
        Window window = windows.get(key);
        if (window == null || window.index != index) {
            window = new Window(index, now);
            windows.put(key, window);
        }
        window.lastSeen = now;
        if (window.used < policy.capacity()) {
            window.used++;
            return RateLimitDecision.allowed(policy.capacity(), policy.capacity() - window.used);
        }
        long remainingNanos = windowNanos - Math.floorMod(now, windowNanos);
        return RateLimitDecision.rejected(policy.capacity(),
                Math.max(1L, (long) Math.ceil(remainingNanos / 1_000_000_000.0)));
    }

    public synchronized int keyCount() {
        return windows.size();
    }

    private static final class Window {
        private final long index;
        private int used;
        private long lastSeen;

        private Window(long index, long lastSeen) {
            this.index = index;
            this.lastSeen = lastSeen;
        }
    }
}
