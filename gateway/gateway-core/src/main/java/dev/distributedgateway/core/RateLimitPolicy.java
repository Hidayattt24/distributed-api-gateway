package dev.distributedgateway.core;

public record RateLimitPolicy(int capacity, double refillTokensPerSecond) {
    public RateLimitPolicy {
        if (capacity < 1 || !Double.isFinite(refillTokensPerSecond) || refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("Rate limit capacity and refill rate must be positive");
        }
    }
}
