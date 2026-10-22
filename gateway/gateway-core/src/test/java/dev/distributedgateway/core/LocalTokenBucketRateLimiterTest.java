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
}
