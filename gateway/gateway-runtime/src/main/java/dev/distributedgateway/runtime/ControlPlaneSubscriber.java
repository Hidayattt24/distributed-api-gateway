package dev.distributedgateway.runtime;

import dev.distributedgateway.core.GatewayConfig;
import dev.distributedgateway.core.GatewayProxy;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Fetches validated snapshots away from the request path. */
public final class ControlPlaneSubscriber implements AutoCloseable {
    private final GatewayProxy proxy;
    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final ScheduledExecutorService executor;
    private final long pollSeconds;

    public ControlPlaneSubscriber(GatewayProxy proxy, URI endpoint, String token, long pollSeconds) {
        this.proxy = Objects.requireNonNull(proxy);
        this.endpoint = Objects.requireNonNull(endpoint);
        if (token == null || token.isBlank() || pollSeconds < 1) {
            throw new IllegalArgumentException("Control plane token and positive poll interval are required");
        }
        this.token = token;
        this.pollSeconds = pollSeconds;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "gateway-config-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0, pollSeconds, TimeUnit.SECONDS);
    }

    public void pollOnce() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Control plane returned HTTP " + response.statusCode());
        }
        Properties properties = new Properties();
        properties.load(new StringReader(response.body()));
        long version = Long.parseLong(properties.getProperty("snapshot.version"));
        if (version > proxy.activeVersion()) {
            GatewayConfig config = GatewayConfig.fromProperties(properties, Map.of(), version);
            proxy.install(config);
            System.out.printf("Applied gateway configuration version %d%n", version);
        }
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception error) {
            System.err.println("Configuration poll failed; serving last valid snapshot: " + error.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
