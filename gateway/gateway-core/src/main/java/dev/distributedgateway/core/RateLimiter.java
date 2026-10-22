package dev.distributedgateway.core;

import reactor.core.publisher.Mono;

public interface RateLimiter {
    Mono<Boolean> allow(String key, RateLimitPolicy policy);
}
