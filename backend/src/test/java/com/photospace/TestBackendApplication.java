package com.photospace;

import com.photospace.devauth.DevAuthConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the backend locally with dev sign-in ({@link DevAuthConfiguration}) enabled:
 *
 * <ul>
 *   <li>{@code ./mvnw spring-boot:test-run} uses a throwaway Testcontainers PostgreSQL.
 *   <li>{@code ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=compose-db} uses the compose.yaml
 *       database instead, so data and sessions survive restarts.
 * </ul>
 */
public class TestBackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(BackendApplication::main)
                .with(TestcontainersConfiguration.class, DevAuthConfiguration.class)
                .run(args);
    }
}
