package dev.distributedgateway.ratelimit;

import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.GatewayMetrics;
import dev.distributedgateway.core.RateLimitDecision;
import dev.distributedgateway.core.RateLimitPolicy;
import dev.distributedgateway.core.RateLimitUnavailableException;
import dev.distributedgateway.core.RateLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;

public final class RedisRateLimiter implements RateLimiter, AutoCloseable {
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) + tonumber(clock[2]) / 1000000
            local requested = tonumber(ARGV[3])

            local data = redis.call('HMGET', key, 'tokens', 'last_updated')
            local tokens = tonumber(data[1])
            local last_updated = tonumber(data[2])

            if tokens == nil then
                tokens = capacity
                last_updated = now
            else
                local elapsed = math.max(0, now - last_updated)
                local refilled = elapsed * refill_rate
                tokens = math.min(capacity, tokens + refilled)
                last_updated = now
            end

            local allowed = 0
            local remaining = 0
            local retry_after = 0

            if tokens >= requested then
                tokens = tokens - requested
                allowed = 1
                remaining = math.floor(tokens)
            else
                local needed = requested - tokens
                retry_after = math.ceil(needed / refill_rate)
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'last_updated', tostring(last_updated))
            local ttl = math.ceil(capacity / refill_rate) + 60
            redis.call('EXPIRE', key, ttl)

            return { allowed, math.floor(capacity), remaining, retry_after }
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisReactiveCommands<String, String> reactive;
    private final RateLimiter localFallback;
    private final Duration operationTimeout;
    private volatile GatewayMetrics metrics;

    public void setMetrics(GatewayMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics required");
    }

    public RedisRateLimiter(String redisUri) {
        this(createClient(redisUri));
    }

    private static RedisClient createClient(String redisUri) {
        RedisURI uri = RedisURI.create(redisUri);
        uri.setTimeout(Duration.ofSeconds(3));
        return RedisClient.create(uri);
    }

    public RedisRateLimiter(RedisClient client) {
        this(client, new LocalTokenBucketRateLimiter(), Duration.ofSeconds(2));
    }

    public RedisRateLimiter(RedisClient client, RateLimiter localFallback, Duration operationTimeout) {
        this.client = Objects.requireNonNull(client, "client required");
        try {
            this.connection = client.connect();
        } catch (RuntimeException error) {
            client.shutdown();
            throw new IllegalStateException("Redis unavailable at gateway startup; gateway did not start", error);
        }
        this.reactive = this.connection.reactive();
        this.localFallback = Objects.requireNonNull(localFallback, "localFallback required");
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout required");
    }

    public RedisRateLimiter(RedisReactiveCommands<String, String> reactive, RateLimiter localFallback) {
        this(reactive, localFallback, Duration.ofSeconds(2));
    }

    public RedisRateLimiter(RedisReactiveCommands<String, String> reactive, RateLimiter localFallback,
            Duration operationTimeout) {
        this.client = null;
        this.connection = null;
        this.reactive = Objects.requireNonNull(reactive, "reactive required");
        this.localFallback = Objects.requireNonNull(localFallback, "localFallback required");
        this.operationTimeout = Objects.requireNonNull(operationTimeout, "operationTimeout required");
    }

    @Override
    public Mono<RateLimitDecision> evaluate(String key, RateLimitPolicy policy) {
        Objects.requireNonNull(key, "key required");
        Objects.requireNonNull(policy, "policy required");

        String redisKey = "gateway:ratelimit:" + key;
        String[] keys = new String[]{redisKey};
        String[] args = new String[]{
                String.valueOf(policy.capacity()),
                String.valueOf(policy.refillTokensPerSecond()),
                "1"
        };

        return Mono.from(reactive.eval(LUA_SCRIPT, ScriptOutputType.MULTI, keys, args))
                .map(raw -> {
                    List<?> list = (List<?>) raw;
                    long allowed = ((Number) list.get(0)).longValue();
                    long limit = ((Number) list.get(1)).longValue();
                    long remaining = ((Number) list.get(2)).longValue();
                    long retryAfter = ((Number) list.get(3)).longValue();
                    return allowed == 1
                            ? RateLimitDecision.allowed(limit, remaining)
                            : RateLimitDecision.rejected(limit, retryAfter);
                })
                .timeout(operationTimeout)
                .onErrorResume(error -> {
                    GatewayMetrics currentMetrics = metrics;
                    if (currentMetrics != null) {
                        currentMetrics.recordRedisFailure(policy.failureMode().name(),
                                error instanceof TimeoutException ? "timeout" : "unavailable");
                    }
                    if (policy.failureMode() == RateLimitPolicy.RedisFailureMode.FAIL_OPEN) {
                        return Mono.just(RateLimitDecision.allowed(policy.capacity(), policy.capacity()));
                    }
                    if (policy.failureMode() == RateLimitPolicy.RedisFailureMode.FAIL_CLOSED) {
                        return Mono.error(new RateLimitUnavailableException(error));
                    }
                    return localFallback.evaluate(key, policy);
                });
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
