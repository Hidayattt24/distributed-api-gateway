package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AccessLoggerTest {
    @Test
    void formatsLogLineAsValidJsonWithRequiredFields() {
        AtomicReference<String> loggedLine = new AtomicReference<>();
        AccessLogger logger = new AccessLogger(loggedLine::set);

        logger.log("GET", "/vets/1", "vets", 200, 15.42, "http://vets:8083", false, "127.0.0.1:54321");

        String json = loggedLine.get();
        assertTrue(json.startsWith("{") && json.endsWith("}"));
        assertTrue(json.contains("\"method\":\"GET\""));
        assertTrue(json.contains("\"path\":\"/vets/1\""));
        assertTrue(json.contains("\"route\":\"vets\""));
        assertTrue(json.contains("\"status\":200"));
        assertTrue(json.contains("\"durationMs\":15.42"));
        assertTrue(json.contains("\"upstream\":\"http://vets:8083\""));
        assertTrue(json.contains("\"rateLimited\":false"));
        assertTrue(json.contains("\"clientIp\":\"127.0.0.1:54321\""));
        assertFalse(json.contains("Authorization"));
        assertFalse(json.contains("X-API-Key"));
    }
}
