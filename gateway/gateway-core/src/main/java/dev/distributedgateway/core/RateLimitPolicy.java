package dev.distributedgateway.core;

public record RateLimitPolicy(
        int capacity,
        double refillTokensPerSecond,
        KeyType keyType,
        RedisFailureMode failureMode) {

    public enum KeyType {
        ROUTE, API_KEY, CLIENT_IP
    }

    public enum RedisFailureMode {
        LOCAL_FALLBACK, FAIL_OPEN
    }

    public RateLimitPolicy(int capacity, double refillTokensPerSecond) {
        this(capacity, refillTokensPerSecond, KeyType.ROUTE, RedisFailureMode.LOCAL_FALLBACK);
    }

    public RateLimitPolicy(int capacity, double refillTokensPerSecond, KeyType keyType) {
        this(capacity, refillTokensPerSecond, keyType, RedisFailureMode.LOCAL_FALLBACK);
    }

    public RateLimitPolicy {
        if (capacity < 1 || !Double.isFinite(refillTokensPerSecond) || refillTokensPerSecond <= 0) {
            throw new IllegalArgumentException("Rate limit capacity and refill rate must be positive");
        }
        if (keyType == null) {
            keyType = KeyType.ROUTE;
        }
        if (failureMode == null) {
            failureMode = RedisFailureMode.LOCAL_FALLBACK;
        }
    }
}
