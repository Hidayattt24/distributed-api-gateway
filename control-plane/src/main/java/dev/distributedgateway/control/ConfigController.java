package dev.distributedgateway.control;

import dev.distributedgateway.core.GatewayConfig;
import java.io.StringWriter;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class ConfigController {
    private final ConfigStore store;

    public ConfigController(ConfigStore store) {
        this.store = store;
    }

    @GetMapping("/admin/config/active")
    public GatewayConfig active() {
        return store.active();
    }

    @GetMapping("/admin/config/versions")
    public List<ConfigStore.VersionInfo> versions() {
        return store.versions();
    }

    @GetMapping("/admin/config/versions/{version}")
    public GatewayConfig version(@PathVariable long version) {
        return store.get(version);
    }

    public record PublishRequest(long expectedVersion, long responseTimeoutSeconds,
            List<GatewayConfig.RouteDefinition> routes) {
    }

    public record RollbackRequest(long expectedVersion) {
    }

    @PostMapping("/admin/config/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayConfig publish(@RequestBody PublishRequest request) {
        return store.publish(request.expectedVersion(), request.responseTimeoutSeconds(), request.routes());
    }

    @PostMapping("/admin/config/rollback/{targetVersion}")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayConfig rollback(@PathVariable long targetVersion, @RequestBody RollbackRequest request) {
        return store.rollback(targetVersion, request.expectedVersion());
    }

    @GetMapping(value = "/internal/config/active", produces = MediaType.TEXT_PLAIN_VALUE)
    public String internalSnapshot() throws Exception {
        StringWriter writer = new StringWriter();
        store.active().toProperties().store(writer, "gateway snapshot");
        return writer.toString();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException error) {
        return error.getMessage();
    }
}
