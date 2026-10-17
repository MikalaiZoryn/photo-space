package com.photospace;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import com.photospace.support.ApiClient;
import com.photospace.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Boots the full application against a clean PostgreSQL container. */
@IntegrationTest
class BackendApplicationIT {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesAllMigrationsToCleanDatabase() {
        List<String> failed = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = false", String.class);
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(failed).isEmpty();
        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void apiHealthReportsDatabaseUp() throws Exception {
        HttpResponse<String> response = get("/api/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").contains("\"database\":\"UP\"");
    }

    @Test
    void actuatorProbesAreUpAndHideDetails() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
            HttpResponse<String> response = get(path);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"").doesNotContain("components");
        }
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        // Anonymous callers are stopped by security first; even a signed-in user finds nothing there.
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
        ApiClient signedIn = new ApiClient(port).signIn("actuator-" + UUID.randomUUID() + "@example.com");
        assertThat(signedIn.get("/actuator/env").status()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
