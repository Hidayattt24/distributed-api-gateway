package dev.distributedgateway.core;

public record RateLimitDecision(boolean allowed, long limit, long remaining, long retryAfterSeconds) {
    public static RateLimitDecision allowed(long limit, long remaining) {
        return new RateLimitDecision(true, limit, remaining, 0);
    }

    public static RateLimitDecision rejected(long limit, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, 0, Math.max(1, retryAfterSeconds));
    }
}
