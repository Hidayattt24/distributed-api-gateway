package dev.distributedgateway.control;

import dev.distributedgateway.core.GatewayConfig;
import dev.distributedgateway.core.UpstreamAllowlist;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConfigStore {
    private final JdbcTemplate jdbc;
    private final UpstreamAllowlist allowlist;

    public ConfigStore(JdbcTemplate jdbc, @Value("${control.seed-file}") String seedFile,
            @Value("${gateway.allowed-upstreams:}") String allowedUpstreams,
            @Value("${gateway.require-allowlist:false}") boolean requireAllowlist) throws IOException {
        this.jdbc = jdbc;
        this.allowlist = UpstreamAllowlist.fromCsv(allowedUpstreams, requireAllowlist);
        jdbc.execute("CREATE TABLE IF NOT EXISTS config_versions (version BIGINT PRIMARY KEY, payload TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS config_meta (id INTEGER PRIMARY KEY, active_version BIGINT NOT NULL)");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM config_meta", Long.class);
        if (count == 0) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(Path.of(seedFile))) {
                properties.load(input);
            }
            GatewayConfig initial = GatewayConfig.fromProperties(properties, System.getenv(), 1);
            allowlist.validate(initial);
            jdbc.update("INSERT INTO config_versions (version, payload) VALUES (?, ?)", 1L, encode(initial));
            jdbc.update("INSERT INTO config_meta (id, active_version) VALUES (1, 1)");
        }
    }

    public GatewayConfig active() {
        Long version = jdbc.queryForObject("SELECT active_version FROM config_meta WHERE id=1", Long.class);
        return get(version);
    }

    public GatewayConfig get(long version) {
        List<String> payloads = jdbc.query("SELECT payload FROM config_versions WHERE version=?",
                (result, row) -> result.getString(1), version);
        if (payloads.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration version not found");
        }
        GatewayConfig config = decode(payloads.getFirst(), version);
        allowlist.validate(config);
        return config;
    }

    public List<VersionInfo> versions() {
        return jdbc.query("SELECT version, created_at FROM config_versions ORDER BY version DESC",
                (result, row) -> new VersionInfo(result.getLong(1), result.getTimestamp(2).toInstant()));
    }

    public record VersionInfo(long version, java.time.Instant createdAt) {
    }

    @Transactional
    public GatewayConfig publish(long expectedVersion, long timeoutSeconds,
            List<GatewayConfig.RouteDefinition> routes) {
        long current = lockedVersion();
        if (current != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active version is " + current);
        }
        GatewayConfig next = new GatewayConfig(current + 1, timeoutSeconds, routes);
        allowlist.validate(next);
        storeAndActivate(next);
        return next;
    }

    @Transactional
    public GatewayConfig rollback(long targetVersion, long expectedVersion) {
        long current = lockedVersion();
        if (current != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Active version is " + current);
        }
        GatewayConfig target = get(targetVersion);
        GatewayConfig next = new GatewayConfig(current + 1, target.responseTimeoutSeconds(), target.routes());
        storeAndActivate(next);
        return next;
    }

    private long lockedVersion() {
        Long version = jdbc.queryForObject("SELECT active_version FROM config_meta WHERE id=1 FOR UPDATE", Long.class);
        return version;
    }

    private void storeAndActivate(GatewayConfig config) {
        jdbc.update("INSERT INTO config_versions (version, payload) VALUES (?, ?)", config.version(), encode(config));
        jdbc.update("UPDATE config_meta SET active_version=? WHERE id=1", config.version());
    }

    private static String encode(GatewayConfig config) {
        StringWriter writer = new StringWriter();
        try {
            config.toProperties().store(writer, "gateway configuration");
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return writer.toString();
    }

    private static GatewayConfig decode(String payload, long version) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(payload));
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        return GatewayConfig.fromProperties(properties, Map.of(), version);
    }
}
