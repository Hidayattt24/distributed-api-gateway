package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalTokenBucketRateLimiterTest {
    @Test
    void consumesCapacityThenRefillsUsingMonotonicTime() {
        AtomicLong time = new AtomicLong();
        RateLimiter limiter = new LocalTokenBucketRateLimiter(time::get);
        RateLimitPolicy policy = new RateLimitPolicy(2, 1);

        assertTrue(limiter.allow("users", policy).block());
        assertTrue(limiter.allow("users", policy).block());
        assertFalse(limiter.allow("users", policy).block());

        time.set(1_000_000_000L);
        assertTrue(limiter.allow("users", policy).block());
        assertFalse(limiter.allow("users", policy).block());
    }

    @Test
    void concurrentRequestsCannotSpendTheSameTokenTwice() throws Exception {
        RateLimiter limiter = new LocalTokenBucketRateLimiter(() -> 0L);
        RateLimitPolicy policy = new RateLimitPolicy(7, 1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                requests.add(() -> limiter.allow("users", policy).block());
            }
            List<Future<Boolean>> outcomes = executor.invokeAll(requests);
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get()) {
                    accepted++;
                }
            }
            assertEquals(7, accepted);
        }
    }

    @Test
    void returnsAccurateRateLimitDecisionAndHeaders() {
        AtomicLong time = new AtomicLong(0L);
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(time::get);
        RateLimitPolicy policy = new RateLimitPolicy(3, 1.0);

        RateLimitDecision d1 = limiter.evaluate("route1", policy).block();
        assertTrue(d1.allowed());
        assertEquals(3, d1.limit());
        assertEquals(2, d1.remaining());

        RateLimitDecision d2 = limiter.evaluate("route1", policy).block();
        assertTrue(d2.allowed());
        assertEquals(1, d2.remaining());

        RateLimitDecision d3 = limiter.evaluate("route1", policy).block();
        assertTrue(d3.allowed());
        assertEquals(0, d3.remaining());

        RateLimitDecision d4 = limiter.evaluate("route1", policy).block();
        assertFalse(d4.allowed());
        assertEquals(0, d4.remaining());
        assertEquals(1, d4.retryAfterSeconds());
    }

    @Test
    void evictsIdleBucketsWhenMaxCapacityReached() {
        AtomicLong time = new AtomicLong(0L);
        long idleNanos = 1_000_000_000L;
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(time::get, 2, idleNanos);
        RateLimitPolicy policy = new RateLimitPolicy(5, 1.0);

        limiter.evaluate("user-1", policy).block();
        limiter.evaluate("user-2", policy).block();
        assertEquals(2, limiter.bucketCount());

        // Advance past idle timeout and refill to full capacity
        time.set(10_000_000_000L);

        // Third user should trigger eviction of idle full buckets
        limiter.evaluate("user-3", policy).block();
        assertTrue(limiter.bucketCount() <= 2);
    }

    @Test
    void rejectsNewKeysWhenAllSlotsAreActiveWithoutExceedingLimit() {
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(() -> 0L, 2, 1_000_000_000L);
        RateLimitPolicy policy = new RateLimitPolicy(2, 1.0);
        assertTrue(limiter.evaluate("first", policy).block().allowed());
        assertTrue(limiter.evaluate("second", policy).block().allowed());
        assertFalse(limiter.evaluate("third", policy).block().allowed());
        assertEquals(2, limiter.bucketCount());
        assertTrue(limiter.evaluate("first", policy).block().allowed());
    }

    @Test
    void concurrentNewKeysNeverExceedCapacity() throws Exception {
        LocalTokenBucketRateLimiter limiter = new LocalTokenBucketRateLimiter(() -> 0L, 3, 1_000_000_000L);
        RateLimitPolicy policy = new RateLimitPolicy(1, 1.0);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<RateLimitDecision>> requests = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                String key = "key-" + index;
                requests.add(() -> limiter.evaluate(key, policy).block());
            }
            int accepted = 0;
            for (Future<RateLimitDecision> outcome : executor.invokeAll(requests)) {
                if (outcome.get().allowed()) {
                    accepted++;
                }
            }
            assertEquals(3, accepted);
            assertEquals(3, limiter.bucketCount());
        }
    }
}
