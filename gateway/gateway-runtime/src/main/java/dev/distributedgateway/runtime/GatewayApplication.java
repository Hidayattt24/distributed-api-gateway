package dev.distributedgateway.runtime;

import dev.distributedgateway.core.GatewayConfig;
import dev.distributedgateway.core.GatewayProxy;
import dev.distributedgateway.core.LocalTokenBucketRateLimiter;
import dev.distributedgateway.core.RateLimiter;
import dev.distributedgateway.ratelimit.RedisRateLimiter;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import reactor.netty.DisposableServer;

public final class GatewayApplication {
    private GatewayApplication() {
    }

    public static void main(String[] args) throws Exception {
        Path configuration = Path.of(System.getenv().getOrDefault("GATEWAY_CONFIG", "config/gateway.properties"));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(configuration)) {
            properties.load(input);
        }
        GatewayConfig initial = GatewayConfig.fromProperties(properties, System.getenv(), 1);
        int port = Integer.parseInt(System.getenv().getOrDefault("GATEWAY_PORT",
                properties.getProperty("gateway.port", "8080")));
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", properties.getProperty("redis.url", ""));
        RateLimiter rateLimiter;
        if (!redisUrl.isBlank()) {
            RedisRateLimiter redisLimiter = new RedisRateLimiter(redisUrl);
            Runtime.getRuntime().addShutdownHook(new Thread(redisLimiter::close));
            rateLimiter = redisLimiter;
        } else {
            rateLimiter = new LocalTokenBucketRateLimiter();
        }

        GatewayProxy proxy = new GatewayProxy(initial, rateLimiter);
        DisposableServer server = proxy.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::disposeNow));

        String controlUrl = System.getenv().getOrDefault("CONTROL_PLANE_URL", "");
        if (!controlUrl.isBlank()) {
            ControlPlaneSubscriber subscriber = new ControlPlaneSubscriber(proxy, URI.create(controlUrl),
                    System.getenv("CONTROL_PLANE_TOKEN"),
                    Long.parseLong(System.getenv().getOrDefault("CONTROL_PLANE_POLL_SECONDS", "2")));
            subscriber.start();
            Runtime.getRuntime().addShutdownHook(new Thread(subscriber::close));
        }
        System.out.printf("Gateway listening on http://localhost:%d; initial configuration version %d%n",
                server.port(), proxy.activeVersion());
        server.onDispose().block();
    }
}
