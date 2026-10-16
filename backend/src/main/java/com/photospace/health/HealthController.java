package com.photospace.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small application-level health endpoint used by the frontend. Infrastructure probes use
 * {@code /actuator/health/liveness} and {@code /actuator/health/readiness} instead.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DatabaseProbe databaseProbe;

    public HealthController(DatabaseProbe databaseProbe) {
        this.databaseProbe = databaseProbe;
    }

    @GetMapping
    public ResponseEntity<HealthResponse> health() {
        boolean databaseUp = databaseProbe.isUp();
        HealthResponse body = new HealthResponse(databaseUp ? "UP" : "DOWN", databaseUp ? "UP" : "DOWN");
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    public record HealthResponse(String status, String database) {}
}
