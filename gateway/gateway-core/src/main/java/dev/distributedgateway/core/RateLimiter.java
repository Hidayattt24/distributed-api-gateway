package dev.distributedgateway.core;

import reactor.core.publisher.Mono;

public interface RateLimiter {
    default Mono<Boolean> allow(String key, RateLimitPolicy policy) {
        return evaluate(key, policy).map(RateLimitDecision::allowed);
    }

    Mono<RateLimitDecision> evaluate(String key, RateLimitPolicy policy);
}
