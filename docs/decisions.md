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
