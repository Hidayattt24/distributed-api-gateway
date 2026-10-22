package dev.distributedgateway.runtime;

import dev.distributedgateway.core.GatewayConfig;
import dev.distributedgateway.core.GatewayProxy;
import dev.distributedgateway.core.GatewayMetrics;
import dev.distributedgateway.core.AccessLogger;
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
        GatewayMetrics metrics = new GatewayMetrics();
        RateLimiter rateLimiter;
        if (!redisUrl.isBlank()) {
            RedisRateLimiter redisLimiter = new RedisRateLimiter(redisUrl);
            redisLimiter.setMetrics(metrics);
            rateLimiter = redisLimiter;
        } else {
            rateLimiter = new LocalTokenBucketRateLimiter();
        }

        GatewayProxy proxy = new GatewayProxy(initial, rateLimiter, metrics, new AccessLogger());
        DisposableServer server = proxy.start(port);

        String controlUrl = System.getenv().getOrDefault("CONTROL_PLANE_URL", "");
        ControlPlaneSubscriber subscriber = null;
        if (!controlUrl.isBlank()) {
            subscriber = new ControlPlaneSubscriber(proxy, URI.create(controlUrl),
                    System.getenv("CONTROL_PLANE_TOKEN"),
                    Long.parseLong(System.getenv().getOrDefault("CONTROL_PLANE_POLL_SECONDS", "2")));
            subscriber.start();
        }

        final ControlPlaneSubscriber activeSubscriber = subscriber;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Gateway shutting down gracefully; draining in-flight requests...");
            proxy.beginDrain();
            if (activeSubscriber != null) {
                try {
                    activeSubscriber.close();
                } catch (Exception ignored) {
                }
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (proxy.metrics().inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                server.disposeNow();
            } catch (Exception ignored) {
            }
            if (rateLimiter instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                }
            }
            System.out.println("Gateway shutdown complete.");
        }));

        System.out.printf("Gateway listening on http://localhost:%d; initial configuration version %d%n",
                server.port(), proxy.activeVersion());
        server.onDispose().block();
    }
}
