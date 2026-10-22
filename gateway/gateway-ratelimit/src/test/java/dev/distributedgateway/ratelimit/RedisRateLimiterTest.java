package dev.distributedgateway.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.RateLimitDecision;
import dev.distributedgateway.core.RateLimitPolicy;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class RedisRateLimiterTest {
    @Test
    void parsesSuccessfulLuaScriptOutput() {
        @SuppressWarnings("unchecked")
        RedisReactiveCommands<String, String> mockReactive = (RedisReactiveCommands<String, String>) Proxy.newProxyInstance(
                RedisReactiveCommands.class.getClassLoader(),
                new Class<?>[]{RedisReactiveCommands.class},
                (proxy, method, args) -> {
                    if ("eval".equals(method.getName())) {
                        return Flux.just(List.of(1L, 10L, 9L, 0L));
                    }
                    return null;
                });

        RedisRateLimiter limiter = new RedisRateLimiter(mockReactive, new LocalTokenBucketRateLimiter(), () -> 100.0);
        RateLimitPolicy policy = new RateLimitPolicy(10, 5.0);

        RateLimitDecision decision = limiter.evaluate("route1", policy).block();
        assertTrue(decision.allowed());
        assertEquals(10, decision.limit());
        assertEquals(9, decision.remaining());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    void fallsBackToLocalTokenBucketWhenRedisFailsUnderLocalFallbackMode() {
        AtomicInteger localFallbackCalls = new AtomicInteger();
        LocalTokenBucketRateLimiter localLimiter = new LocalTokenBucketRateLimiter();

        @SuppressWarnings("unchecked")
        RedisReactiveCommands<String, String> brokenRedis = (RedisReactiveCommands<String, String>) Proxy.newProxyInstance(
                RedisReactiveCommands.class.getClassLoader(),
                new Class<?>[]{RedisReactiveCommands.class},
                (proxy, method, args) -> {
                    if ("eval".equals(method.getName())) {
                        return Flux.error(new RedisException("Redis cluster unavailable"));
                    }
                    return null;
                });

        RedisRateLimiter limiter = new RedisRateLimiter(brokenRedis, (key, policy) -> {
            localFallbackCalls.incrementAndGet();
            return localLimiter.evaluate(key, policy);
        }, () -> 100.0);

        RateLimitPolicy policy = new RateLimitPolicy(2, 1.0, RateLimitPolicy.KeyType.ROUTE,
                RateLimitPolicy.RedisFailureMode.LOCAL_FALLBACK);

        // First 2 calls succeed via local fallback
        RateLimitDecision d1 = limiter.evaluate("route1", policy).block();
        assertTrue(d1.allowed());

        RateLimitDecision d2 = limiter.evaluate("route1", policy).block();
        assertTrue(d2.allowed());

        // Third call rejected by local fallback
        RateLimitDecision d3 = limiter.evaluate("route1", policy).block();
        assertFalse(d3.allowed());
        assertEquals(3, localFallbackCalls.get());
    }

    @Test
    void failsOpenWhenRedisFailsUnderFailOpenMode() {
        @SuppressWarnings("unchecked")
        RedisReactiveCommands<String, String> brokenRedis = (RedisReactiveCommands<String, String>) Proxy.newProxyInstance(
                RedisReactiveCommands.class.getClassLoader(),
                new Class<?>[]{RedisReactiveCommands.class},
                (proxy, method, args) -> Flux.error(new RedisException("Redis cluster timeout")));

        RedisRateLimiter limiter = new RedisRateLimiter(brokenRedis, new LocalTokenBucketRateLimiter(), () -> 100.0);
        RateLimitPolicy policy = new RateLimitPolicy(5, 1.0, RateLimitPolicy.KeyType.ROUTE,
                RateLimitPolicy.RedisFailureMode.FAIL_OPEN);

        RateLimitDecision decision = limiter.evaluate("route1", policy).block();
        assertTrue(decision.allowed());
        assertEquals(5, decision.limit());
        assertEquals(5, decision.remaining());
    }
}
