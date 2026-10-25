package dev.distributedgateway.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:controltest;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "control.token=test-secret",
        "control.seed-file=../config/gateway.properties",
        "gateway.allowed-upstreams=http://localhost:8081,http://localhost:8082,http://localhost:8083",
        "gateway.require-allowlist=true"
})
class ControlPlaneIntegrationTest {
    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void publishesWithVersionCheckAndRollsBack() throws Exception {
        String base = "http://localhost:" + port;
        assertEquals(401, request(base + "/admin/config/active", "GET", null, false).statusCode());
        assertTrue(request(base + "/admin/config/active", "GET", null, true).body().contains("\"version\":1"));

        String publish = """
                {"expectedVersion":1,"responseTimeoutSeconds":4,"routes":[
                  {"id":"demo","pathPrefix":"/demo","upstreams":["http://localhost:8081"],"policy":null}
                ]}
                """;
        assertEquals(400, request(base + "/admin/config/versions", "POST", publish.replace("localhost:8081", "localhost:9999"), true).statusCode());
        assertEquals(201, request(base + "/admin/config/versions", "POST", publish, true).statusCode());
        assertTrue(request(base + "/internal/config/active", "GET", null, true).body().contains("snapshot.version=2"));
        assertEquals(409, request(base + "/admin/config/versions", "POST", publish, true).statusCode());
        assertEquals(400, request(base + "/admin/config/versions", "POST", """
                {"expectedVersion":2,"responseTimeoutSeconds":4,"routes":[]}
                """, true).statusCode());
        assertEquals(201, request(base + "/admin/config/rollback/1", "POST",
                "{\"expectedVersion\":2}", true).statusCode());
        String active = request(base + "/admin/config/active", "GET", null, true).body();
        assertTrue(active.contains("\"version\":3"));
        assertTrue(active.contains("\"id\":\"vets\""));
    }

    private HttpResponse<String> request(String url, String method, String body, boolean authorized) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        if (authorized) {
            builder.header("Authorization", "Bearer test-secret");
        }
        if (body == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
