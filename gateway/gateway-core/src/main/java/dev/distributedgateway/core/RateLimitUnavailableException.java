package dev.distributedgateway.core;

/** The configured quota store is unavailable and policy forbids a local decision. */
public final class RateLimitUnavailableException extends RuntimeException {
    public RateLimitUnavailableException(Throwable cause) {
        super("Distributed rate limit unavailable", cause);
    }
}
