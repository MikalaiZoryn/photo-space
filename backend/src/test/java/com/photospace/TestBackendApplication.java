package com.photospace;

import org.springframework.boot.SpringApplication;

/**
 * Runs the backend against a Testcontainers PostgreSQL instead of compose.yaml:
 * {@code ./mvnw spring-boot:test-run}.
 */
public class TestBackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(BackendApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
