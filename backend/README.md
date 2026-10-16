# backend

The Java 25 and Spring Boot 4.1 modular monolith, built with the Maven wrapper. The planned modules are listed in ARCHITECTURE.md §3–4. Each module becomes a package under `com.photospace`.

- `com.photospace.health`: `GET /api/health`, which the frontend uses.
- `com.photospace.identity`: Google OIDC sign-in, `SecurityConfig`, server-side sessions, users and external identities, and `/api/me`.
- `com.photospace.workspaces`: workspaces, memberships, and the central `WorkspaceAccessInterceptor`, which authorizes every `/api/workspaces/{workspaceId}/**` request.
- `com.photospace.shared.web`: correlation IDs and problem (error) responses.
- `src/test/.../devauth`: dev sign-in for local runs and tests (`./mvnw spring-boot:test-run`). It is never packaged.
- `src/main/resources/db/migration`: Flyway migrations.
- `src/test/.../*Test.java`: unit and slice tests (`./mvnw test`).
- `src/test/.../*IT.java`: Testcontainers integration tests (`./mvnw verify`, needs Docker).

See the root README for run and test commands.
