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

## D6: Authentication and server-side sessions

- **Date:** 2026-10-15 (Milestone 2)
- **Decision:**
  - Google sign-in uses Spring Security `oauth2Login` (OIDC, scopes `openid email` only). Spring Security validates state, nonce, and the ID token's signature, issuer, audience, and expiry. Google is registered only when `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` are set.
  - Users are keyed on `(provider, subject)` in `external_identities`. Email is a display snapshot only.
  - After sign-in, the authentication is replaced by a `SignedInUser(userId)` principal. The ID token, Google's access token, and profile claims are not stored. The access token is discarded right away, because login doesn't need it.
  - Sessions are server-side, in PostgreSQL via Spring Session JDBC (V3 migration). The `SESSION` cookie is `HttpOnly`, `Secure`, and `SameSite=Lax` (Lax because the Google callback is a cross-site top-level redirect), with a 12-hour idle timeout. Sign-in rotates the session ID.
  - CSRF uses Spring Security's SPA mode. The `XSRF-TOKEN` cookie is echoed back in the `X-XSRF-TOKEN` header on every state-changing request.
  - Unauthenticated API calls get a 401 problem body, never a redirect, and don't create a session.
- **Why:** sessions in the database survive restarts and work across several ECS tasks without sticky sessions. Keeping tokens out of the session limits what a session-store leak would expose.
- **Consequences:** session attributes use Java serialization, so a Spring Security upgrade can invalidate existing sessions. Users sign in again; nothing else is lost. Behind the Milestone 8 load balancer, forwarded headers must be configured so the OIDC redirect URI uses the public HTTPS origin.

## D7: Persistence with JdbcClient, not JPA

- **Date:** 2026-10-15 (Milestone 2)
- **Decision:** Modules use Spring's `JdbcClient` with explicit SQL. JPA/Hibernate is not added.
- **Why:** tenant scoping is visible in every query (`WHERE workspace_id = :workspaceId`) and is easy to review. There is no hidden lazy loading or dirty checking, and it adds no dependency. Settles the open point from D4.
- **Consequences:** row mapping is written by hand. Revisit if mapping boilerplate becomes a real cost.

## D8: Central workspace authorization and dev sign-in

- **Date:** 2026-10-15 (Milestone 2)
- **Decision:**
  - Every workspace-owned API lives under `/api/workspaces/{workspaceId}/…`. One MVC interceptor checks membership for that whole path and fails closed. A missing or malformed ID, an unknown workspace, and someone else's workspace all return the same 404. Handlers receive the verified `WorkspaceContext` and scope SQL by its ID. Role checks (`requireOwner()`) return 403 to members.
  - `ApiRouteAuthorizationIT` fails the build if any `/api` route is outside a short allowlist and not under that prefix, or if a workspace route doesn't take a `WorkspaceContext`.
  - Roles are `OWNER` and `MEMBER` only.
  - Dev sign-in (`POST /api/dev/login`, a password-less "dev" identity) lives in `src/test` and runs through `./mvnw spring-boot:test-run`. It is never compiled into the application jar, so no profile or property can enable it in a deployed environment. Dev identities use their own provider, so they can't collide with Google identities.
- **Consequences:** new modules get authorization by putting their routes under the workspace prefix. Inviting members is not built yet (out of Milestone 2 scope); integration tests insert memberships directly.

## D9: Session times are entered as workspace wall-clock time and stored as UTC

- **Date:** 2026-10-15 (Milestone 3)
- **Decision:**
  - The API takes a session's start and end as local date-times without an offset (`startLocal`, `endLocal`, e.g. `2026-11-07T10:30`), meaning wall-clock time in the workspace's time zone. The server converts them with `java.time` and stores UTC `TIMESTAMPTZ` values.
  - A local time that doesn't exist because clocks jump forward is rejected with a field error. A time that happens twice because clocks fall back resolves to the earlier instant. Years outside 2000–2100 are rejected.
  - Responses return both the UTC instants (`startsAt`, `endsAt`) and the same moments as wall-clock times in the workspace's *current* zone (`startLocal`, `endLocal`, `timezone`). The UI displays the instants with `Intl.DateTimeFormat` in that zone, never in the browser's own zone.
  - Changing the workspace time zone keeps each session's instant; only its displayed wall-clock time changes.
- **Why:** the server owns the workspace zone, and `java.time` makes the daylight-saving policy explicit and unit-testable. The browser then needs no time zone arithmetic beyond formatting.
- **Consequences:** future calendar sync (Milestone 10) works with the stored instants. All-day sessions aren't modelled; a session is either unscheduled or has a start time.

## D10: Record lifecycle conventions for workspace resources

- **Date:** 2026-10-15 (Milestone 3)
- **Decision:**
  - **Archive, don't delete.** Clients and sessions get `archived_at`. Archived records leave default lists (`?archived=true` lists them), stay readable, and can't be edited or change status until restored. Archive and restore are idempotent `POST …/archive` and `POST …/restore` actions. A session keeps its link to a client archived later, but can't be newly linked to an archived client.
  - **Optimistic locking.** Editable records carry `version`. `PUT` takes `{"version": n, "<resource>": {…fields}}` and returns 409 with a fixed, safe `detail` if the version is stale.
  - **Status changes have their own endpoint.** `POST …/sessions/{id}/status` is the only way to change a session's status. It enforces the transition table in `SessionStatus` and returns 409 for a disallowed change. New sessions always start as `INQUIRY`, and `PUT` never touches status. Responses list `allowedStatuses`, so the UI doesn't duplicate the rules.
  - **Lists are paged** with `page` (from 0) and `size` (1–100, default 25), sorted stably (with `id` as the final tiebreaker), and return `{items, page, size, totalItems}`.
  - **Malformed IDs in the path return 404**, the same as unknown and foreign ones. Malformed query parameters return 400 with per-field `errors`.
  - **Cross-module links stay inside the workspace at the database level too.** A link to another module's record uses a composite foreign key on `(workspace_id, <id>)`, as `photo_sessions` does with `clients`.
- **Why:** archiving keeps history for galleries, content, and tasks that will link to these records. A single status endpoint gives later workflows (Milestone 7) and auditing one place to hook into.
- **Consequences:** later resources (galleries, tasks) follow the same conventions. Hard deletion for data-retention requests is a separate, later feature.
