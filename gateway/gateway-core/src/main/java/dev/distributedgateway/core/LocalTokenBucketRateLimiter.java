package dev.distributedgateway.core;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;

public final class LocalTokenBucketRateLimiter implements RateLimiter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public LocalTokenBucketRateLimiter() {
        this(System::nanoTime);
    }

    LocalTokenBucketRateLimiter(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    @Override
    public Mono<Boolean> allow(String key, RateLimitPolicy policy) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(policy);
        return Mono.fromSupplier(() -> consume(key, policy));
    }

    private boolean consume(String key, RateLimitPolicy policy) {
        long now = nanoTime.getAsLong();
        AtomicBoolean allowed = new AtomicBoolean();
        buckets.compute(key, (ignored, current) -> {
            Bucket bucket = current == null ? new Bucket(policy.capacity(), now) : current;
            long elapsedNanos = Math.max(0, now - bucket.lastRefillNanos);
            double refilled = elapsedNanos / 1_000_000_000.0 * policy.refillTokensPerSecond();
            bucket.tokens = Math.min(policy.capacity(), bucket.tokens + refilled);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                allowed.set(true);
            }
            return bucket;
        });
        return allowed.get();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
