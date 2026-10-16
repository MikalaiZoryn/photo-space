# backend

The Java 25 and Spring Boot 4.1 modular monolith, built with the Maven wrapper. The planned modules are listed in ARCHITECTURE.md §3–4. Each module becomes a package under `com.photospace`.

- `com.photospace.health`: `GET /api/health`, which the frontend uses.
- `src/main/resources/db/migration`: Flyway migrations.
- `src/test/.../*Test.java`: unit and slice tests (`./mvnw test`).
- `src/test/.../*IT.java`: Testcontainers integration tests (`./mvnw verify`, needs Docker).

See the root README for run and test commands.
