package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LocalFixedWindowRateLimiterTest {
    @Test
    void resetsAtBoundaryAndExposesBoundaryBurst() {
        AtomicLong now = new AtomicLong(1_900_000_000L);
        var limiter = new LocalFixedWindowRateLimiter(now::get, 10, 10_000_000_000L);
        var policy = new RateLimitPolicy(2, 1.0);
        assertTrue(limiter.allow("route", policy).block());
        assertTrue(limiter.allow("route", policy).block());
        assertFalse(limiter.allow("route", policy).block());
        now.set(2_000_000_000L);
        assertTrue(limiter.allow("route", policy).block());
        assertTrue(limiter.allow("route", policy).block());
        assertFalse(limiter.allow("route", policy).block());
    }

    @Test
    void concurrentRequestsCannotExceedOneWindowCapacity() throws Exception {
        var limiter = new LocalFixedWindowRateLimiter(() -> 0L, 10, 1_000_000_000L);
        var policy = new RateLimitPolicy(7, 1.0);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> requests = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(ignored -> (Callable<Boolean>) () -> limiter.allow("route", policy).block())
                    .toList();
            int accepted = 0;
            for (var result : executor.invokeAll(requests)) {
                if (result.get()) accepted++;
            }
            assertEquals(7, accepted);
        }
    }

    @Test
    void capsKeyMemoryAndEvictsIdleKeys() {
        AtomicLong now = new AtomicLong();
        var limiter = new LocalFixedWindowRateLimiter(now::get, 1, 1_000_000_000L);
        var policy = new RateLimitPolicy(2, 1.0);
        assertTrue(limiter.allow("first", policy).block());
        assertFalse(limiter.allow("second", policy).block());
        now.set(2_000_000_000L);
        assertTrue(limiter.allow("second", policy).block());
        assertEquals(1, limiter.keyCount());
    }
}
