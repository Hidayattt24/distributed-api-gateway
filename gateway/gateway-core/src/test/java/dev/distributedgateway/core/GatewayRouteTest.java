package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayRouteTest {
    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayRoute("", "/valid", URI.create("http://localhost:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayRoute("id", "no-slash", URI.create("http://localhost:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayRoute("id", "/trailing/", URI.create("http://localhost:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayRoute("id", "/valid", URI.create("ftp://localhost:8080")));
        assertThrows(IllegalArgumentException.class,
                () -> new GatewayRoute("id", "/valid", List.of()));
    }

    @Test
    void matchesPathPrefixCorrectly() {
        GatewayRoute route = new GatewayRoute("vets", "/vets", URI.create("http://localhost:8083"));
        assertTrue(route.matches("/vets"));
        assertTrue(route.matches("/vets/"));
        assertTrue(route.matches("/vets/specialties"));
        assertTrue(route.matches("/vets?sort=name"));
        assertFalse(route.matches("/vets-extra"));
        assertFalse(route.matches("/other"));
    }
}
