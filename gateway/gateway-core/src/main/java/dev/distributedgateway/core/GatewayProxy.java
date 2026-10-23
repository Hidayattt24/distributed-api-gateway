package dev.distributedgateway.core;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final long MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_IN_FLIGHT_REQUESTS = 256;

    private static final class RequestTooLargeException extends RuntimeException {
    }

    private static final Set<String> HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "host");
    private static final Set<String> UNTRUSTED_CLIENT_IDENTITY_HEADERS = Set.of(
            "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto",
            "x-real-ip", "x-original-url", "x-rewrite-url");

    private final AtomicReference<Snapshot> active;
    private final RateLimiter rateLimiter;
    private final LoadBalancer loadBalancer;
    private final GatewayMetrics metrics;
    private final AccessLogger accessLogger;
    private boolean draining;

    private static final class Observation {
        private final long startNanos = System.nanoTime();
        private final String method;
        private final String path;
        private final String clientIp;
        private String routeId;
        private String upstream;
        private int status = 500;
        private boolean rateLimited;

        private Observation(String method, String path, String clientIp) {
            this.method = method;
            this.path = path;
            this.clientIp = clientIp;
        }
    }

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
        this(routes, responseTimeout, rateLimitPolicies, rateLimiter, loadBalancer, apiKeyVerifier,
                new GatewayMetrics(), new AccessLogger());
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout,
            Map<String, RateLimitPolicy> rateLimitPolicies, RateLimiter rateLimiter,
            LoadBalancer loadBalancer, ApiKeyVerifier apiKeyVerifier,
            GatewayMetrics metrics, AccessLogger accessLogger) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.loadBalancer = Objects.requireNonNull(loadBalancer);
        this.metrics = Objects.requireNonNull(metrics);
        this.accessLogger = Objects.requireNonNull(accessLogger);
        this.active = new AtomicReference<>(new Snapshot(0, List.copyOf(routes), Map.copyOf(rateLimitPolicies),
                Objects.requireNonNull(apiKeyVerifier), HttpClient.create().responseTimeout(responseTimeout)));
        this.metrics.setConfigVersion(0);
    }

    public GatewayProxy(GatewayConfig config, RateLimiter rateLimiter) {
        this(config, rateLimiter, new GatewayMetrics(), new AccessLogger());
    }

    public GatewayProxy(GatewayConfig config, RateLimiter rateLimiter, GatewayMetrics metrics, AccessLogger accessLogger) {
        this(config.compiledRoutes(), Duration.ofSeconds(config.responseTimeoutSeconds()),
                config.compiledPolicies(), rateLimiter, new LoadBalancer(), config.apiKeyVerifier(),
                metrics, accessLogger);
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
        metrics.setConfigVersion(config.version());
    }

    public long activeVersion() {
        return active.get().version();
    }

    public GatewayMetrics metrics() {
        return metrics;
    }

    public LoadBalancer loadBalancer() {
        return loadBalancer;
    }

    public DisposableServer start(int port) {
        return HttpServer.create().port(port).handle(this::handle).bindNow();
    }

    /** Bind this listener only to a trusted interface or keep it inside a private network. */
    public DisposableServer startManagement(String host, int port) {
        return HttpServer.create().host(host).port(port).handle(this::handleManagement).bindNow();
    }

    public synchronized void beginDrain() {
        draining = true;
    }

    private synchronized boolean isDraining() {
        return draining;
    }

    private synchronized boolean admitRequest() {
        if (draining || metrics.inFlightCount() >= MAX_IN_FLIGHT_REQUESTS) {
            return false;
        }
        metrics.requestStarted();
        return true;
    }

    private Publisher<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        String uri = request.uri();
        if (uri.equals("/_gateway") || uri.startsWith("/_gateway/")) {
            return response.status(HttpResponseStatus.NOT_FOUND).sendString(Mono.just("No matching route")).then();
        }
        if (isDraining()) {
            return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                    .sendString(Mono.just("Gateway shutting down")).then();
        }
        if (!admitRequest()) {
            return response.status(HttpResponseStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "1")
                    .sendString(Mono.just("Gateway busy or shutting down")).then();
        }

        String method = request.method().name();
        String path = uri.split("\\?", 2)[0];
        SocketAddress remote = request.remoteAddress();
        String clientIp = remote != null ? remote.toString() : "-";
        Observation observation = new Observation(method, path, clientIp);
        String contentLength = request.requestHeaders().get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_REQUEST_BYTES) {
                    observation.status = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code();
                    return observe(response.status(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE)
                            .sendString(Mono.just("Request body too large")).then(), observation);
                }
            } catch (NumberFormatException invalidLength) {
                observation.status = HttpResponseStatus.BAD_REQUEST.code();
                return observe(response.status(HttpResponseStatus.BAD_REQUEST)
                        .sendString(Mono.just("Invalid Content-Length")).then(), observation);
            }
        }

        Snapshot snapshot = active.get();
        GatewayRoute route = snapshot.routes().stream()
                .filter(candidate -> candidate.matches(uri)).findFirst().orElse(null);

        if (route == null) {
            observation.status = HttpResponseStatus.NOT_FOUND.code();
            return observe(response.status(HttpResponseStatus.NOT_FOUND)
                    .sendString(Mono.just("No matching route")).then(), observation);
        }
        observation.routeId = route.id();

        RateLimitPolicy policy = snapshot.policies().get(route.id());
        if (policy == null) {
            return observe(forward(request, response, route, null, snapshot, observation), observation);
        }

        var rateLimitKey = RateLimitKeyResolver.resolveKey(request, route, policy, snapshot.apiKeyVerifier());
        if (rateLimitKey.isEmpty()) {
            observation.status = HttpResponseStatus.UNAUTHORIZED.code();
            return observe(response.status(HttpResponseStatus.UNAUTHORIZED)
                    .sendString(Mono.just("Invalid API key")).then(), observation);
        }

        return rateLimiter.evaluate(rateLimitKey.orElseThrow(), policy)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        return forward(request, response, route, decision, snapshot, observation);
                    }
                    observation.status = HttpResponseStatus.TOO_MANY_REQUESTS.code();
                    observation.rateLimited = true;
                    metrics.recordRateLimited(route.id(), policy.keyType().name());
                    response.status(HttpResponseStatus.TOO_MANY_REQUESTS)
                            .header("X-RateLimit-Limit", String.valueOf(decision.limit()))
                            .header("X-RateLimit-Remaining", String.valueOf(decision.remaining()))
                            .header("Retry-After", String.valueOf(decision.retryAfterSeconds()));
                    return response.sendString(Mono.just("Rate limit exceeded")).then();
                })
                .transform(result -> observe(result, observation));
    }

    private Publisher<Void> handleManagement(HttpServerRequest request, HttpServerResponse response) {
        if (!request.method().name().equals("GET")) {
            return response.status(HttpResponseStatus.METHOD_NOT_ALLOWED).send().then();
        }
        String uri = request.uri();
        if ("/_gateway/version".equals(uri)) {
            return response.header("Content-Type", "text/plain")
                    .sendString(Mono.just(Long.toString(activeVersion()))).then();
        }
        if ("/_gateway/metrics".equals(uri)) {
            return response.header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .sendString(Mono.just(metrics.scrape())).then();
        }
        if ("/_gateway/health".equals(uri)) {
            return handleHealth(response);
        }
        return response.status(HttpResponseStatus.NOT_FOUND).send().then();
    }

    private Mono<Void> forward(HttpServerRequest request, HttpServerResponse response, GatewayRoute route,
            RateLimitDecision decision, Snapshot snapshot, Observation observation) {
        URI upstream = loadBalancer.select(route);
        if (upstream == null) {
            observation.status = HttpResponseStatus.BAD_GATEWAY.code();
            metrics.recordUpstreamFailure(route.id(), "none", "all_unavailable");
            response.status(HttpResponseStatus.BAD_GATEWAY);
            return response.sendString(Mono.just("All upstreams unavailable")).then();
        }
        observation.upstream = upstream.toString();

        String target = upstream.toString().replaceAll("/+$", "") + request.uri();
        long upstreamStart = System.nanoTime();
        AtomicLong receivedBytes = new AtomicLong();
        return snapshot.client().headers(headers -> copyHeaders(request.requestHeaders(), headers,
                        snapshot.policies().get(route.id()) != null
                                && snapshot.policies().get(route.id()).keyType() == RateLimitPolicy.KeyType.API_KEY,
                        true))
                .request(request.method())
                .uri(target)
                .send((outboundRequest, outbound) -> outbound.send(request.receive()
                        .handle((ByteBuf buffer, reactor.core.publisher.SynchronousSink<ByteBuf> sink) -> {
                            if (receivedBytes.addAndGet(buffer.readableBytes()) > MAX_REQUEST_BYTES) {
                                sink.error(new RequestTooLargeException());
                            } else {
                                sink.next(buffer.retain());
                            }
                        })))
                .response((upstreamResponse, body) -> {
                    loadBalancer.reportSuccess(upstream);
                    metrics.setUpstreamHealth(route.id(), upstream.toString(), true);

                    int statusCode = upstreamResponse.status().code();
                    observation.status = statusCode;
                    response.status(upstreamResponse.status());
                    copyHeaders(upstreamResponse.responseHeaders(), response.responseHeaders(), false, false);
                    if (decision != null) {
                        response.header("X-RateLimit-Limit", String.valueOf(decision.limit()));
                        response.header("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
                    }
                    return response.send(body.retain()).then()
                            .doOnSuccess(ignored -> metrics.recordUpstreamDuration(route.id(), upstream.toString(),
                                    Duration.ofNanos(System.nanoTime() - upstreamStart)));
                })
                .then()
                .onErrorResume(error -> {
                    if (isRequestTooLarge(error)) {
                        if (response.hasSentHeaders()) {
                            return Mono.error(error);
                        }
                        observation.status = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE.code();
                        response.status(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
                        return response.sendString(Mono.just("Request body too large")).then();
                    }
                    loadBalancer.reportFailure(upstream);
                    metrics.setUpstreamHealth(route.id(), upstream.toString(), false);
                    boolean timeout = error instanceof TimeoutException || error instanceof ReadTimeoutException;
                    String reason = timeout ? "timeout" : "unavailable";
                    metrics.recordUpstreamFailure(route.id(), upstream.toString(), reason);

                    if (response.hasSentHeaders()) {
                        return Mono.error(error);
                    }
                    observation.status = timeout ? HttpResponseStatus.GATEWAY_TIMEOUT.code() : HttpResponseStatus.BAD_GATEWAY.code();
                    response.status(timeout ? HttpResponseStatus.GATEWAY_TIMEOUT : HttpResponseStatus.BAD_GATEWAY);
                    return response.sendString(Mono.just(timeout ? "Upstream timeout" : "Upstream unavailable")).then();
                });
    }

    private Mono<Void> observe(Mono<Void> result, Observation observation) {
        return result.doFinally(signal -> {
            if (signal == reactor.core.publisher.SignalType.CANCEL && observation.status == 500) {
                observation.status = 499;
            }
            Duration duration = Duration.ofNanos(Math.max(0, System.nanoTime() - observation.startNanos));
            try {
                metrics.recordRequest(observation.routeId != null ? observation.routeId : "_none",
                        observation.method, observation.status, duration);
                accessLogger.log(observation.method, observation.path, observation.routeId,
                        observation.status, duration.toNanos() / 1_000_000.0,
                        observation.upstream, observation.rateLimited, observation.clientIp);
            } finally {
                metrics.requestFinished();
            }
        });
    }

    private static boolean isRequestTooLarge(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof RequestTooLargeException) {
                return true;
            }
        }
        return false;
    }

    private Publisher<Void> handleHealth(HttpServerResponse response) {
        Snapshot snapshot = active.get();
        boolean allHealthy = true;
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"status\":\"UP\",\"version\":").append(snapshot.version());
        sb.append(",\"inFlight\":").append(metrics.inFlightCount()).append(",\"routes\":{");
        int rIndex = 0;
        for (GatewayRoute r : snapshot.routes()) {
            if (rIndex++ > 0) sb.append(",");
            sb.append("\"").append(r.id()).append("\":{");
            int uIndex = 0;
            for (URI u : r.upstreams()) {
                if (uIndex++ > 0) sb.append(",");
                boolean h = loadBalancer.isHealthy(u);
                if (!h) allHealthy = false;
                sb.append("\"").append(u).append("\":").append(h ? "\"UP\"" : "\"DOWN\"");
            }
            sb.append("}");
        }
        sb.append("}}");
        HttpResponseStatus status = allHealthy ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE;
        return response.status(status).header("Content-Type", "application/json").sendString(Mono.just(sb.toString())).then();
    }

    private static void copyHeaders(HttpHeaders source, HttpHeaders target,
            boolean stripApiKey, boolean stripClientIdentity) {
        Set<String> connectionHeaders = source.getAll(HttpHeaderNames.CONNECTION).stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        source.forEach(entry -> {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_HEADERS.contains(name) && !connectionHeaders.contains(name)
                    && !(stripApiKey && name.equals("x-api-key"))
                    && !(stripClientIdentity && UNTRUSTED_CLIENT_IDENTITY_HEADERS.contains(name))) {
                target.add(entry.getKey(), entry.getValue());
            }
        });
    }
}
