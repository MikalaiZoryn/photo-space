# Technical decisions

A short record of decisions that the project documents left open. Add new entries at the bottom.

## D1: Infrastructure as code: AWS CDK in TypeScript

- **Date:** 2026-10-15 (Milestone 0)
- **Decision:** All shared AWS infrastructure is defined with AWS CDK, written in TypeScript, in `infra/`.
- **Why:** CDK is AWS-native and covers ECS Fargate, RDS, S3, SQS, and IAM directly. TypeScript is CDK's most widely used language and matches the frontend toolchain.
- **Consequences:** Terraform is not used. AWS SAM is added only if a Lambda-specific workflow clearly justifies it. `cdk.context.json` is committed, and `cdk.out/` is ignored.

## D2: Backend build tool: Maven

- **Date:** 2026-10-15 (Milestone 0)
- **Decision:** The Spring Boot backend uses Maven, with the Maven wrapper (`mvnw`) committed.
- **Consequences:** Builds and CI call `./mvnw`. Gradle is not used.

## D3: Monorepo with a modular monolith backend

- **Date:** 2026-10-15 (Milestone 0)
- **Decision:** One repository holds `frontend/`, `backend/`, `infra/`, and `docs/`. The backend is one Spring Boot application with module boundaries, as described in ARCHITECTURE.md §3–4.
- **Consequences:** No microservices are created without a documented operational reason.

## D4: Local development and test stack

- **Date:** 2026-10-15 (Milestone 1)
- **Decision:**
  - Local PostgreSQL 18 runs from `compose.yaml` at the repository root, bound to `127.0.0.1` only.
  - Backend integration tests use Testcontainers (`postgres:18`) and run under Maven Failsafe (`*IT.java`). Unit and slice tests run under Surefire (`*Test.java`).
  - The frontend uses Vitest with jsdom and Testing Library for tests, and oxlint (the current Vite template default) for linting. It does not use ESLint.
  - In development, the frontend calls the backend same-origin through the Vite `/api` proxy, so no CORS configuration is needed.
- **Why:** Tests run against real PostgreSQL rather than H2, so migrations and SQL behave as in production. Docker is the only extra requirement, and CI runners already have it.
- **Consequences:** `./mvnw verify` needs Docker. The backend uses `spring-boot-starter-jdbc` until the first entities arrive. Milestone 2 decides between JPA and plain JDBC for persistence.

## D5: Health endpoints

- **Date:** 2026-10-15 (Milestone 1)
- **Decision:** `GET /api/health` is a small app-facing endpoint. It returns UP or DOWN for the app and the database, and HTTP 503 when the database is down. Infrastructure probes use the actuator endpoints `/actuator/health/{liveness,readiness}`. Only the `health` actuator endpoint is exposed, and its details are hidden.
- **Consequences:** Milestone 8 load balancer and ECS health checks point at the actuator probes, not at `/api/health`.
