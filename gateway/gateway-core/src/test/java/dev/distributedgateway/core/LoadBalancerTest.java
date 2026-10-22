package dev.distributedgateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoadBalancerTest {
    @Test
    void distributesRequestsRoundRobinAcrossHealthyUpstreams() {
        URI u1 = URI.create("http://localhost:8081");
        URI u2 = URI.create("http://localhost:8082");
        URI u3 = URI.create("http://localhost:8083");
        GatewayRoute route = new GatewayRoute("test", "/test", List.of(u1, u2, u3));

        LoadBalancer lb = new LoadBalancer();
        assertEquals(u1, lb.select(route));
        assertEquals(u2, lb.select(route));
        assertEquals(u3, lb.select(route));
        assertEquals(u1, lb.select(route));
    }

    @Test
    void excludesFailedUpstreamAndRecoversAfterCooldown() {
        AtomicLong time = new AtomicLong(1_000_000_000L);
        Duration cooldown = Duration.ofSeconds(5);
        LoadBalancer lb = new LoadBalancer(cooldown, time::get);

        URI u1 = URI.create("http://localhost:8081");
        URI u2 = URI.create("http://localhost:8082");
        GatewayRoute route = new GatewayRoute("test", "/test", List.of(u1, u2));

        // Initial selection: u1
        assertEquals(u1, lb.select(route));

        // u1 fails
        lb.reportFailure(u1);
        assertFalse(lb.isHealthy(u1));

        // Subsequent selections should skip u1 and only choose healthy u2
        assertEquals(u2, lb.select(route));
        assertEquals(u2, lb.select(route));

        // Advance time past cooldown (5s = 5_000_000_000 nanos)
        time.addAndGet(5_000_000_001L);
        assertTrue(lb.isHealthy(u1));

        // u1 is eligible again and rejoins rotation
        URI next = lb.select(route);
        assertTrue(next.equals(u1) || next.equals(u2));
    }

    @Test
    void returnsNullWhenAllUpstreamsAreUnhealthy() {
        AtomicLong time = new AtomicLong(0L);
        LoadBalancer lb = new LoadBalancer(Duration.ofSeconds(10), time::get);

        URI u1 = URI.create("http://localhost:8081");
        URI u2 = URI.create("http://localhost:8082");
        GatewayRoute route = new GatewayRoute("test", "/test", List.of(u1, u2));

        lb.reportFailure(u1);
        lb.reportFailure(u2);

        assertNull(lb.select(route));
    }
}
