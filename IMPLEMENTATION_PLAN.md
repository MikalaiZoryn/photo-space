# Photography Business SaaS --- Implementation Plan

## 1. How Claude Code should use this plan

Implement one milestone at a time. Before coding, inspect the repository
and existing conventions. Do not overwrite existing work or introduce
dependencies without a clear reason.

For each milestone: 1. Read `CLAUDE.md`, `SYSTEM_OVERVIEW.md`, and
`ARCHITECTURE.md`. 2. Inspect the current code and identify the smallest
coherent change. 3. State the implementation approach briefly. 4.
Implement the milestone without starting unrelated future milestones. 5.
Add or update tests. 6. Run relevant tests, formatting, linting, and
builds. 7. Update this plan and `README.md` with what changed and the
exact commands to verify it. 8. Summarize files changed, decisions, test
results, known gaps, and the next recommended task.

Do not claim a test passed unless it was actually run. If an external
credential or AWS account is unavailable, provide a local
mock/development path and document what remains unverified.

## 2. Delivery principles

-   Prefer a working vertical slice over broad scaffolding.
-   Keep the backend a modular monolith.
-   Keep the project runnable locally at every milestone.
-   Use migrations for schema changes; never rely on manually created
    production schema.
-   Add authorization tests whenever a workspace-owned resource is
    added.
-   Use interfaces at external provider boundaries, but do not create
    abstractions with no real second use or clear testing benefit.
-   Do not implement autonomous consequential actions without explicit
    approval and auditability.
-   Keep documentation current; treat the three project documents as the
    source of truth.
-   Avoid speculative features and premature microservices.
-   Keep credentials out of source control, fixtures, browser storage,
    and logs.

## 3. Milestones

## Milestone 0 --- Repository discovery and project rules

### Tasks

-   Inspect the repository and identify existing code, package managers,
    build tools, and conventions.
-   Create or update `CLAUDE.md` with concise project rules and links to
    these documents.
-   Create/update `README.md` with project purpose, repository layout,
    prerequisites, and development commands.
-   Establish the monorepo structure for `frontend/`, `backend/`, and
    `infra/` only if not already present.
-   Decide on one primary infrastructure-as-code tool for shared AWS
    infrastructure (CDK or Terraform); record the choice before building
    infrastructure.
-   Add `.gitignore`, `.editorconfig`, and safe example environment
    configuration if needed.

### Acceptance criteria

-   A new developer can understand the project layout and how to start.
-   No secrets are present in tracked files.
-   Existing useful code is preserved.
-   The infrastructure-as-code choice is documented.

## Milestone 1 --- Local development foundation

### Tasks

-   Set up a React + TypeScript + Vite frontend.
-   Set up a Java + Spring Boot backend.
-   Add local PostgreSQL using Docker Compose or the repository's
    established local-dev mechanism.
-   Configure Flyway and an initial migration.
-   Add health endpoints and a basic frontend-to-backend API request.
-   Add backend unit-test and integration-test setup.
-   Add frontend lint/type-check/test/build commands.
-   Add GitHub Actions CI for backend checks and frontend checks.
-   Document exact commands and required tool versions.

### Acceptance criteria

-   One documented command or a small documented set of commands starts
    the local development stack.
-   Backend connects to local PostgreSQL and Flyway migrations run from
    a clean database.
-   Frontend can call the backend.
-   CI runs relevant checks on a pull request.
-   No cloud credentials are required to run basic local tests.

## Milestone 2 --- Identity, workspaces, and authorization

### Tasks

-   Implement Google OpenID Connect sign-in using a maintained Spring
    Security-supported approach.
-   Validate OIDC state/nonce and token claims correctly; use Google
    `sub` as the stable external identity identifier.
-   Create user, external identity, workspace, and workspace membership
    models/migrations.
-   Establish a server-side application session with secure cookie
    settings.
-   Add a minimal onboarding flow that creates or selects a workspace.
-   Implement a central workspace authorization mechanism.
-   Require workspace authorization for all workspace-owned APIs.
-   Add tests for unauthenticated access, authorized access, and
    cross-workspace access denial.
-   Keep Gmail and Calendar API scopes out of the login flow.

### Acceptance criteria

-   A configured Google account can sign in locally.
-   A user can create or select a workspace.
-   Protected APIs reject unauthenticated requests.
-   A user cannot read or modify another workspace's records by changing
    an ID or request parameter.
-   OAuth secrets remain server-side and are not logged.
-   The local development flow is documented for environments without
    Google credentials, using a safe test-auth profile only if it cannot
    accidentally be enabled in production.

## Milestone 3 --- Clients and photography sessions

### Tasks

-   Implement client CRUD with workspace scoping.
-   Implement session CRUD, status transitions, date/time,
    timezone-aware display, notes, and optional client association.
-   Define validation rules and lifecycle statuses.
-   Build simple frontend pages for clients and sessions.
-   Add pagination and useful empty/loading/error states.
-   Add tests for validation, domain behavior, and tenant isolation.

### Acceptance criteria

-   Photographer can create, view, edit, and archive clients and
    sessions.
-   Session records can be associated with clients.
-   Dates are stored consistently and displayed in the workspace
    timezone.
-   Every query and mutation is workspace-scoped.
-   Core workflows have automated tests.

## Milestone 4 --- Secure photo upload and processing

### Tasks

-   Add photo asset metadata and processing status.
-   Configure a private S3 bucket per environment through infrastructure
    as code.
-   Implement an upload authorization endpoint that returns a
    short-lived presigned URL for a server-generated object key.
-   Enforce workspace/session ownership, allowed file types, file-size
    limits, and quotas.
-   Implement upload confirmation or an S3 event path to trigger
    processing.
-   Add an asynchronous worker for file validation, metadata extraction,
    thumbnails, and previews.
-   Persist job status, retries, and safe failure summaries.
-   Add cleanup for abandoned pending uploads.
-   Keep local tests possible through mocks or a local S3-compatible
    test setup if useful.

### Acceptance criteria

-   Authorized users can upload photos to private storage.
-   Users cannot upload into another workspace's session.
-   Invalid file types and oversized uploads are rejected or
    quarantined.
-   Original objects are not public.
-   Processing is asynchronous and status is visible.
-   Retries do not create duplicate asset records or inconsistent
    derivatives.
-   Authorization and upload validation have automated tests.

## Milestone 5 --- Private galleries and downloads

### Tasks

-   Implement galleries and gallery-asset associations.
-   Build a simple gallery management UI with ordering and publication
    status.
-   Implement expiring, revocable share tokens; store token hashes
    rather than raw tokens.
-   Add a public-facing gallery route that resolves only the specific
    valid token and applies gallery policy.
-   Generate short-lived access URLs for previews and downloads.
-   Support gallery view/download settings and record relevant
    access/download events.
-   Add expiry, revocation, and unauthorized-access tests.
-   Document retention and deletion behavior for gallery assets.

### Acceptance criteria

-   Photographer can create a gallery from authorized assets and
    generate a share link.
-   A valid link grants access only to that gallery and only until
    expiry/revocation.
-   Revoked, expired, malformed, and unrelated tokens do not grant
    access.
-   S3 objects remain private.
-   Gallery access does not expose workspace management APIs or other
    galleries.
-   Download behavior follows gallery settings.

## Milestone 6 --- AI content studio: first vertical slice

### Tasks

-   Add a photographer brand profile with voice, audience, and content
    preferences.
-   Define a provider-neutral content-generation interface and typed
    input/output models.
-   Implement an initial generation flow for selected photos plus
    session notes.
-   Generate multiple caption options and optional calls to
    action/hashtag suggestions; support carousel/story ideas if
    straightforward.
-   Validate structured model output and handle provider
    errors/timeouts/rate limits.
-   Persist generation batches and editable/versioned drafts.
-   Build UI to select photos, request generation, edit, save, and
    regenerate drafts.
-   Record provider/model and prompt-template version without storing
    secrets.
-   Make the AI provider configurable and mockable for tests.
-   Add safeguards against inventing client details and ensure the user
    explicitly approves content before external publication.

### Acceptance criteria

-   Photographer can select assets from a session and generate content
    drafts.
-   Drafts persist after reload and can be edited.
-   Failed generation is reported clearly and can be retried.
-   Provider-specific SDK models do not leak into domain entities or
    public API DTOs.
-   Tests cover output validation, persistence, errors, and workspace
    isolation.
-   No generated content is automatically published.

## Milestone 7 --- Tasks and deterministic workflows

### Tasks

-   Implement task CRUD, due dates, status, optional assignment, and
    links to client/session.
-   Add a small set of deterministic rules, such as creating a follow-up
    task when a session reaches a defined status.
-   Add durable workflow/job status, retries, idempotency keys, and a
    dead-letter queue or equivalent failure path.
-   Implement transactional outbox or another reliable database-to-queue
    dispatch mechanism where required.
-   Add audit events for meaningful state changes.
-   Add a simple task list and session-linked task view.

### Acceptance criteria

-   Photographer can create, complete, and filter tasks.
-   A configured event creates the expected task once, even if an event
    is delivered more than once.
-   Failed jobs are observable and recoverable.
-   Sensitive actions and workflow outcomes are auditable.
-   Business state changes do not depend on an LLM making an unvalidated
    decision.

## Milestone 8 --- AWS deployment and operational hardening

This milestone can be performed earlier for a small test environment,
but should be completed before relying on the system for real client
data.

### Tasks

-   Provision the minimum AWS environment through the chosen IaC tool:
    networking as needed, ECS Fargate API/worker, RDS PostgreSQL,
    private S3, SQS/DLQ, IAM roles, logs, and secrets.
-   Configure TLS, domain/routing, environment separation, and safe
    secret injection.
-   Add database backups and document/test restore steps.
-   Add CloudWatch metrics and alarms for API failures, job failures,
    queue depth, and processing issues.
-   Add deployment health checks and a rollback procedure.
-   Verify least-privilege IAM and that buckets are not public.
-   Add CI/CD deployment only after build/test workflows are reliable.

### Acceptance criteria

-   A documented staging environment can be deployed reproducibly.
-   API and workers use distinct least-privilege roles where practical.
-   No secrets are baked into container images or frontend assets.
-   Backups and restore steps are documented; a restore test has been
    performed.
-   Key failures generate useful alerts.
-   Deployment and rollback procedures are documented.

## Milestone 9 --- Gmail read/draft integration (later; compliance gate)

### Tasks

-   Confirm current Google OAuth scope classifications, verification
    requirements, privacy policy requirements, and any restricted-scope
    security assessment before implementation and public launch.
-   Implement a separate explicit Gmail connection flow, separate from
    login.
-   Request only the minimum scopes required for the selected feature.
-   Implement safe incremental sync using provider IDs/cursors and
    idempotency.
-   Minimize email content retention; define disconnect, revoke, and
    deletion behavior.
-   Start by preparing drafts for user review; do not automatically
    send.
-   Add audit records for sync and draft actions.
-   Add tests for revoked tokens, expired tokens, duplicate events, and
    tenant isolation.

### Acceptance criteria

-   Gmail access is optional and independent from Google sign-in.
-   Users can connect and disconnect the integration.
-   Tokens are encrypted and never exposed to the browser or logs.
-   Email sync is idempotent and failures are recoverable.
-   Drafts require user review.
-   Public-launch compliance status is documented and verified against
    current Google guidance.

## Milestone 10 --- Calendar integration and approved actions

### Tasks

-   Define source-of-truth and conflict behavior before bidirectional
    sync.
-   Add Google Calendar connection and sync cursors only after internal
    sessions/tasks are stable.
-   Handle timezones, daylight-saving transitions, duplicates,
    deletions, and out-of-order changes.
-   Add approval workflow for sending email, changing confirmed
    bookings, or publishing content.
-   Store the requested action, approving user, approval time, execution
    result, and correlation ID.
-   Add a kill switch to disable automated execution.

### Acceptance criteria

-   Sync does not silently overwrite conflicting external edits.
-   Repeated notifications do not duplicate events or actions.
-   Consequential actions cannot execute without required
    authorization/approval.
-   Every execution is auditable and can be disabled operationally.

## Milestone 11 --- Commercialization readiness

### Tasks

-   Add workspace onboarding and account/workspace lifecycle workflows.
-   Define export, deletion, retention, and support procedures.
-   Add usage quotas and cost controls for storage, AI, and background
    jobs.
-   Add billing only after validating the product's value and pricing
    model.
-   Review terms, privacy disclosures, client-photo consent, data
    processing, and provider agreements.
-   Conduct a security review and test tenant isolation thoroughly.
-   Add basic product analytics that avoid collecting unnecessary
    personal data.
-   Re-evaluate roles, support tooling, and service boundaries based on
    real usage.

### Acceptance criteria

-   A new photography business can onboard without developer
    intervention for normal flows.
-   Data export and deletion work as documented.
-   Quotas prevent runaway storage/AI costs.
-   Privacy/security disclosures match actual data flows.
-   Tenant isolation and sensitive-action audit tests pass.
-   No microservice extraction is performed without a documented
    operational reason.

## 4. Cross-cutting Definition of Done

A task is complete only when applicable items are satisfied:

-   [ ] Acceptance criteria are met.
-   [ ] Tests added/updated and actually run.
-   [ ] Workspace authorization is enforced and tested.
-   [ ] Database changes use Flyway migrations.
-   [ ] Error handling and loading/empty states are reasonable.
-   [ ] Logs contain correlation IDs but no secrets or unnecessary
    personal data.
-   [ ] External calls have timeouts and failure handling.
-   [ ] Background work is idempotent and retryable where applicable.
-   [ ] Documentation and local commands are current.
-   [ ] No unrelated features or speculative abstractions were added.

## 5. First Claude Code task

Start with **Milestone 0 only**. Inspect the repository, preserve
existing work, create/update the project guidance and documentation, and
report the proposed implementation sequence. Do not attempt to build the
entire product in one run.

After Milestone 0 is reviewed, implement Milestone 1. Continue
sequentially and keep each change small enough to review and test.

## Progress

### Milestone 0: done (2026-10-15)

-   The repository started empty, so there was no existing code to preserve.
-   Added `CLAUDE.md`, `README.md`, `.gitignore`, `.editorconfig`, and
    `docs/decisions.md`.
-   Recorded decisions: AWS CDK in TypeScript (D1), Maven (D2), and a
    monorepo with a modular monolith (D3).
-   Created the `frontend/`, `backend/`, `infra/`, and `docs/` folders.
    Each placeholder README explains when its contents arrive.
-   `.env.example` is deferred to Milestone 1, where the first real
    configuration (the local database) is consumed. Adding it now would
    be speculative.
-   Verification: `git ls-files` lists no secrets or `.env` files. There
    is no build or test yet.
-   Known gap: the dev machine does not yet have Java, Node, Docker, or
    the AWS CLI. These must be installed before Milestone 1 (see the
    README prerequisites).

### Milestone 1: done (2026-10-15)

-   **Local stack:** `compose.yaml` runs PostgreSQL 18 on
    `127.0.0.1:5432`. `.env.example` documents the optional overrides
    (port and datasource).
-   **Backend** (`backend/`): Spring Boot 4.1.1 on Java 25, with the
    Maven wrapper (Maven 3.10). It includes Web MVC, Actuator, JDBC,
    Flyway, and the PostgreSQL driver.
    -   `V1__baseline.sql` creates the Flyway history and records
        schema conventions. Business tables start in Milestone 2.
    -   `GET /api/health` reports app and database status, or HTTP 503
        when the database is down. The actuator exposes only liveness
        and readiness probes, with details hidden. Error responses
        never include stack traces or messages.
-   **Backend tests:**
    -   `HealthControllerTest` (WebMvc slice): UP and DOWN responses.
    -   `BackendApplicationIT` (Testcontainers `postgres:18`): Flyway
        applies V1 to a clean database, `/api/health` is UP, the
        actuator probes are UP with no details, and `/actuator/env`
        is not exposed.
-   **Frontend** (`frontend/`): Vite 8, React 19, and TypeScript 6.
    -   The home page calls `/api/health` through the Vite dev proxy
        and shows loading, UP/DOWN, and unreachable states.
    -   Vitest and Testing Library tests cover all four states.
    -   oxlint runs with warnings treated as errors.
-   **CI:** `.github/workflows/ci.yml` runs on pull requests and
    pushes to `main`. The backend job runs `./mvnw -B verify`. The
    frontend job runs `npm ci`, lint, typecheck, test, and build. The
    workflow was checked with actionlint.
-   **Decisions:** D4 (local and test stack) and D5 (health endpoints)
    in `docs/decisions.md`.
-   **Verified locally on 2026-10-15:**
    -   `./mvnw verify`: 2 unit tests and 4 integration tests passed.
    -   `npm run lint && npm run typecheck && npm test && npm run build`:
        4 tests passed and the build succeeded.
    -   Manual end-to-end check: `docker compose up -d --wait`, then
        `./mvnw spring-boot:run` and `npm run dev`. `curl
        localhost:5173/api/health` returned `{"status":"UP","database":"UP"}`
        through the proxy. The `flyway_schema_history` table in the
        Compose database showed V1 succeeded. After `docker compose stop
        postgres`, the endpoint returned HTTP 503 with the database DOWN.
-   **Known gaps:**
    -   CI has not run on GitHub yet. It runs on the first push or pull
        request.
    -   There is no backend formatter or static analysis yet. It will be
        added if review friction justifies it.
    -   Structured JSON logs and correlation IDs are not set up yet. They
        come with the first real API surface in Milestone 2.
    -   With the database down, `/api/health` can wait up to Hikari's default
        30s connection timeout before it returns 503. Tune this in
        Milestone 8.

### Milestone 2: done (2026-10-15)

-   **Schema:** `V2__identity_and_workspaces.sql` adds `users`,
    `external_identities` (unique `provider` + `subject`),
    `workspaces`, and `workspace_members` (unique `workspace_id` +
    `user_id`, role `OWNER`/`MEMBER`). `V3__spring_session.sql` adds
    the Spring Session JDBC tables.
-   **Sign-in** (`identity` module): Spring Security `oauth2Login`
    with Google, scopes `openid email` only. Spring Security validates
    state, nonce, and the ID token. Users are provisioned by Google
    `sub`. After sign-in the session holds only a
    `SignedInUser(userId)`; ID and access tokens are not stored.
    Google is enabled only when `GOOGLE_CLIENT_ID` and
    `GOOGLE_CLIENT_SECRET` are set.
-   **Sessions:** stored server-side in PostgreSQL (Spring Session
    JDBC). The cookie is `HttpOnly`, `Secure`, and `SameSite=Lax`, with
    a 12-hour idle timeout, and the session ID rotates at sign-in.
    CSRF uses SPA cookie-to-header mode. Unauthenticated calls get a
    401 problem body, never a redirect, and create no session.
    Sign-out is `POST /api/auth/logout`.
-   **Authorization** (`workspaces` module): every workspace-owned
    API lives under `/api/workspaces/{workspaceId}/…`, and one
    interceptor checks membership. It fails closed: foreign, unknown,
    and malformed IDs all return 404. Handlers take the verified
    `WorkspaceContext`. Owner-only actions return 403 to members.
    `ApiRouteAuthorizationIT` fails the build for any new `/api` route
    outside the allowlist or the workspace prefix.
-   **Dev sign-in** for working without Google credentials: in
    `src/test` only, run through `./mvnw spring-boot:test-run`. It is
    never packaged, so it cannot be enabled in production.
-   **Cross-cutting:** a correlation-ID filter (`X-Correlation-Id`
    header, MDC, log pattern) and RFC 9457 problem responses that
    include `correlationId` and per-field validation `errors`.
-   **Frontend:** sign-in page (Google link, dev form, failed-sign-in
    notice), onboarding to create the first workspace (with the
    browser time zone as default), a workspace selector remembered in
    localStorage, a workspace page with members, and sign-out. The
    `fetch` wrapper `src/api/client.ts` adds the CSRF header. No new
    frontend dependencies.
-   **Backend dependencies added:** security, oauth2-client,
    session-jdbc, and validation starters.
-   **Decisions:** D6 (authentication and sessions), D7 (`JdbcClient`
    instead of JPA), and D8 (central workspace authorization and dev
    sign-in).
-   **Verified locally on 2026-10-15:**
    -   `cd backend && ./mvnw verify`: 4 unit tests and 27 integration
        tests passed.
        -   `AuthenticationIT`: 401s with no session created, CSRF
            required, cookie attributes, session row in PostgreSQL,
            session ID rotation, sign-out invalidates the session,
            Google redirect has only `openid email` plus state and
            nonce, and a forged-state callback is rejected.
        -   `WorkspaceIsolationIT`: cross-workspace read, list, and
            update denial; foreign, unknown, and malformed IDs are
            indistinguishable; members get 403 on owner actions;
            validation errors; client-supplied IDs and roles are
            ignored.
        -   `IdentityServiceIT`: identity keyed on `sub`, not email.
        -   `ApiRouteAuthorizationIT`: the route guard.
        -   `OidcLoginSuccessHandlerTest`: only the user ID reaches
            the session.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 17 tests passed and the build succeeded.
    -   Manual run, `spring-boot:test-run -Dspring-boot.run.profiles=compose-db`
        plus `npm run dev`, all through the Vite proxy:
        -   The Compose database migrated from V1 to V3.
        -   Dev sign-in, create workspace (403 without CSRF, 201
            with it), list members, then sign-out (the old session
            returns 401).
        -   A second user got 404 with a correlation ID for the first
            user's workspace.
        -   With a fake Google client ID, the authorization redirect
            used `redirect_uri=http://localhost:5173/api/auth/oauth2/callback/google`
            with `scope=openid email`, and a forged callback
            redirected to `/?signInError`.
        -   The client secret never appeared in logs, and the
            session row held only the security context.
    -   Fixed during verification: Vite's string proxy shorthand sets
        `changeOrigin: true`, which made the Google redirect URI point
        at `:8080`. The proxy now keeps the `Host` header.
-   **Known gaps:**
    -   A real Google account sign-in has not been completed: no OAuth
        client credentials were available here. Everything up to the
        redirect to Google, and the callback rejection path, is
        verified. Follow README "Option B" to verify it with real
        credentials.
    -   Inviting members to a workspace is not built; the
        `MEMBER` role is exercised only through tests.
    -   Logs are plain text with correlation IDs. JSON structured logs
        come with deployment (Milestone 8).
    -   Forwarded-header handling for the public HTTPS origin behind a
        load balancer is left to Milestone 8 (see D6).
    -   No rate limiting on sign-in endpoints yet (Milestone 8 or 11).

### Milestone 3: next

Clients and photography sessions, scoped under
`/api/workspaces/{workspaceId}/`.
