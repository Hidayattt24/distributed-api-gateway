package dev.distributedgateway.core;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.timeout.ReadTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
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
            "te", "trailer", "transfer-encoding", "upgrade", "host");

    private final List<GatewayRoute> routes;
    private final Map<String, RateLimitPolicy> rateLimitPolicies;
    private final RateLimiter rateLimiter;
    private final HttpClient client;

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout) {
        this(routes, responseTimeout, Map.of(), new LocalTokenBucketRateLimiter());
    }

    public GatewayProxy(List<GatewayRoute> routes, Duration responseTimeout,
            Map<String, RateLimitPolicy> rateLimitPolicies, RateLimiter rateLimiter) {
        this.routes = List.copyOf(routes);
        this.rateLimitPolicies = Map.copyOf(rateLimitPolicies);
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.client = HttpClient.create().responseTimeout(responseTimeout);
    }

    public DisposableServer start(int port) {
        return HttpServer.create().port(port).handle(this::handle).bindNow();
    }

    private Publisher<Void> handle(HttpServerRequest request, HttpServerResponse response) {
        GatewayRoute route = routes.stream().filter(candidate -> candidate.matches(request.uri())).findFirst().orElse(null);
        if (route == null) {
            return response.status(HttpResponseStatus.NOT_FOUND)
                    .sendString(Mono.just("No matching route"))
                    .then();
        }

        RateLimitPolicy policy = rateLimitPolicies.get(route.id());
        Mono<Boolean> decision = policy == null
                ? Mono.just(true)
                : rateLimiter.allow(route.id(), policy);
        return decision.flatMap(allowed -> allowed
                ? forward(request, response, route)
                : response.status(HttpResponseStatus.TOO_MANY_REQUESTS)
                        .sendString(Mono.just("Rate limit exceeded"))
                        .then());
    }

    private Mono<Void> forward(HttpServerRequest request, HttpServerResponse response, GatewayRoute route) {
        String target = route.upstream().toString().replaceAll("/+$", "") + request.uri();
        return client.headers(headers -> copyHeaders(request.requestHeaders(), headers))
                .request(request.method())
                .uri(target)
                .send((outboundRequest, outbound) -> outbound.send(request.receive().retain()))
                .response((upstreamResponse, body) -> {
                    response.status(upstreamResponse.status());
                    copyHeaders(upstreamResponse.responseHeaders(), response.responseHeaders());
                    return response.send(body.retain()).then();
                })
                .then()
                .onErrorResume(error -> {
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
