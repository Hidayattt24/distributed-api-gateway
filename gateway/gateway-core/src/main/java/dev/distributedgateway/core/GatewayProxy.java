package dev.distributedgateway.core;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public final class GatewayProxy {
    private static final Set<String> HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "host");

    private final AtomicReference<Snapshot> active;
    private final RateLimiter rateLimiter;
    private final LoadBalancer loadBalancer;

    private record Snapshot(long version, List<GatewayRoute> routes,
            Map<String, RateLimitPolicy> policies, ApiKeyVerifier apiKeyVerifier, HttpClient client) {
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout) {
        this(routes, responseTimeout, Map.of(), new LocalTokenBucketRateLimiter());
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout,
            Map<String, RateLimitPolicy> rateLimitPolicies, RateLimiter rateLimiter) {
        this(routes, responseTimeout, rateLimitPolicies, rateLimiter, new LoadBalancer());
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout,
            Map<String, RateLimitPolicy> rateLimitPolicies, RateLimiter rateLimiter,
            LoadBalancer loadBalancer) {
        this(routes, responseTimeout, rateLimitPolicies, rateLimiter, loadBalancer, new ApiKeyVerifier(Map.of()));
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout,
            Map<String, RateLimitPolicy> rateLimitPolicies, RateLimiter rateLimiter,
            LoadBalancer loadBalancer, ApiKeyVerifier apiKeyVerifier) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.loadBalancer = Objects.requireNonNull(loadBalancer);
        this.active = new AtomicReference<>(new Snapshot(0, List.copyOf(routes), Map.copyOf(rateLimitPolicies),
                Objects.requireNonNull(apiKeyVerifier), HttpClient.create().responseTimeout(responseTimeout)));
    }

    public GatewayProxy(GatewayConfig config, RateLimiter rateLimiter) {
        this(config.compiledRoutes(), Duration.ofSeconds(config.responseTimeoutSeconds()),
                config.compiledPolicies(), rateLimiter, new LoadBalancer(), config.apiKeyVerifier());
        install(config);
    }

    /** All derived objects are built before the active pointer changes. */
    public synchronized void install(GatewayConfig config) {
        Objects.requireNonNull(config);
        if (config.version() <= active.get().version()) {
            throw new IllegalArgumentException("Configuration version must increase");
        }
        Snapshot next = new Snapshot(config.version(), config.compiledRoutes(), config.compiledPolicies(),
                config.apiKeyVerifier(), HttpClient.create()
                        .responseTimeout(Duration.ofSeconds(config.responseTimeoutSeconds())));
        active.set(next);
    }

    public long activeVersion() {
        return active.get().version();
    }

    public DisposableServer start(int port) {
        return HttpServer.create().port(port).handle(this::handle).bindNow();
    }

    private Publisher<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        if ("/_gateway/version".equals(request.uri())) {
            return response.header("Content-Type", "text/plain")
                    .sendString(Mono.just(Long.toString(activeVersion()))).then();
        }
        Snapshot snapshot = active.get();
        GatewayRoute route = snapshot.routes().stream()
                .filter(candidate -> candidate.matches(request.uri())).findFirst().orElse(null);
        if (route == null) {
            return response.status(HttpResponseStatus.NOT_FOUND)
                    .sendString(Mono.just("No matching route"))
                    .then();
        }

        RateLimitPolicy policy = snapshot.policies().get(route.id());
        if (policy == null) {
            return forward(request, response, route, null, snapshot);
        }

        var rateLimitKey = RateLimitKeyResolver.resolveKey(request, route, policy, snapshot.apiKeyVerifier());
        if (rateLimitKey.isEmpty()) {
            return response.status(HttpResponseStatus.UNAUTHORIZED)
                    .sendString(Mono.just("Invalid API key"))
                    .then();
        }
        return rateLimiter.evaluate(rateLimitKey.orElseThrow(), policy)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return forward(request, response, route, decision, snapshot);
                    }
                    response.status(HttpResponseStatus.TOO_MANY_REQUESTS)
                            .header("X-RateLimit-Limit", String.valueOf(decision.limit()))
                            .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                            .header("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                    return response.sendString(Mono.just("Rate limit exceeded")).then();
                });
    }

    private Mono<Void> forward(HttpServerRequest request, HttpServerResponse response, GatewayRoute route,
            RateLimitDecision decision, Snapshot snapshot) {
        URI upstream = loadBalancer.select(route);
        if (upstream == null) {
            response.status(HttpResponseStatus.BAD_GATEWAY);
            return response.sendString(Mono.just("All upstreams unavailable")).then();
        }
        String target = upstream.toString().replaceAll("/+$", "") + request.uri();
        return snapshot.client().headers(headers -> copyHeaders(request.requestHeaders(), headers))
                .request(request.method())
                .uri(target)
                .send((outboundRequest, outbound) -> outbound.send(request.receive().retain()))
                .response((upstreamResponse, body) -> {
                    loadBalancer.reportSuccess(upstream);
                    response.status(upstreamResponse.status());
                    copyHeaders(upstreamResponse.responseHeaders(), response.responseHeaders());
                    if (decision != null) {
                        response.header("X-RateLimit-Limit", String.valueOf(decision.limit()));
                        response.header("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
                    }
                    return response.send(body.retain()).then();
                })
                .then()
                .onErrorResume(error -> {
                    loadBalancer.reportFailure(upstream);
                    if (response.hasSentHeaders()) {
                        return Mono.error(error);
                    }
                    boolean timeout = error instanceof TimeoutException || error instanceof ReadTimeoutException;
                    response.status(timeout ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY);
                    return response.sendString(Mono.just(timeout ? "Upstream timeout" : "Upstream unavailable")).then();
                });
    }

    private static void copyHeaders(HttpHeaders source, HttpHeaders target) {
        Set<String> connectionHeaders = source.getAll(HttpHeaderNames.CONNECTION).stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        source.forEach(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_HEADERS.contains(name) && !connectionHeaders.contains(name)) {
                target.add(entry.getKey(), entry.getValue());
            }
        });
    }
}
