package dev.distributedgateway.core;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import reactor.core.publisher.Mono;

public final class LocalTokenBucketRateLimiter implements RateLimiter {
    private static final int DEFAULT_MAX_BUCKETS = 10_000;
    private static final long DEFAULT_IDLE_NANOS = Duration.ofMinutes(2).toNanos();

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;
    private final int maxBuckets;
    private final long idleTimeoutNanos;

    public LocalTokenBucketRateLimiter() {
        this(System::nanoTime, DEFAULT_MAX_BUCKETS, DEFAULT_IDLE_NANOS);
    }

    public LocalTokenBucketRateLimiter(LongSupplier nanoTime) {
        this(nanoTime, DEFAULT_MAX_BUCKETS, DEFAULT_IDLE_NANOS);
    }

    public LocalTokenBucketRateLimiter(LongSupplier nanoTime, int maxBuckets, long idleTimeoutNanos) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        if (maxBuckets < 1 || idleTimeoutNanos < 1) {
            throw new IllegalArgumentException("maxBuckets and idleTimeoutNanos must be positive");
        }
        this.maxBuckets = maxBuckets;
        this.idleTimeoutNanos = idleTimeoutNanos;
    }

    @Override
    public Mono<RateLimitDecision> evaluate(String key, RateLimitPolicy policy) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(policy);
        return Mono.fromSupplier(() -> consume(key, policy));
    }

    private synchronized RateLimitDecision consume(String key, RateLimitPolicy policy) {
        long now = nanoTime.getAsLong();
        if (!buckets.containsKey(key) && buckets.size() >= maxBuckets) {
            evictIdle(now);
            if (buckets.size() >= maxBuckets) {
                return RateLimitDecision.rejected(policy.capacity(),
                        Math.max(1L, (long) Math.ceil(idleTimeoutNanos / 1_000_000_000.0)));
            }
        }

        AtomicReference<RateLimitDecision> decisionRef = new AtomicReference<>();
        buckets.compute(key, (ignored, current) -> {
            Bucket bucket = current == null ? new Bucket(policy.capacity(), now) : current;
            long elapsedNanos = Math.max(0, now - bucket.lastRefillNanos);
            double refilled = elapsedNanos / 1_000_000_000.0 * policy.refillTokensPerSecond();
            bucket.tokens = Math.min(policy.capacity(), bucket.tokens + refilled);
            bucket.lastRefillNanos = now;
            bucket.lastSeenNanos = now;

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                long remaining = (long) Math.floor(bucket.tokens);
                decisionRef.set(RateLimitDecision.allowed(policy.capacity(), remaining));
            } else {
                double needed = 1.0 - bucket.tokens;
                long retryAfter = (long) Math.ceil(needed / policy.refillTokensPerSecond());
                decisionRef.set(RateLimitDecision.rejected(policy.capacity(), retryAfter));
            }
            return bucket;
        });
        return decisionRef.get();
    }

    private void evictIdle(long now) {
        buckets.entrySet().removeIf(entry ->
                Math.max(0, now - entry.getValue().lastSeenNanos) > idleTimeoutNanos);
    }

    public synchronized int bucketCount() {
        return buckets.size();
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private long lastSeenNanos;

        private Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
            this.lastSeenNanos = lastRefillNanos;
        }
    }
}
