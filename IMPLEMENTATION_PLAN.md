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
        -   2026-10-16: a real Google account (a test user on an
            External OAuth client in Testing status) signed in through
            http://localhost:5173 with README "Option B": `.env` with
            `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, then
            `set -a; source .env; set +a; (cd backend && ./mvnw spring-boot:run)`.
            `/api/auth/options` listed only Google, and the
            authorization redirect asked for `scope=openid email` with
            state, nonce, and PKCE.
    -   Fixed during verification: Vite's string proxy shorthand sets
        `changeOrigin: true`, which made the Google redirect URI point
        at `:8080`. The proxy now keeps the `Host` header.
-   **Known gaps:**
    -   Inviting members to a workspace is not built; the
        `MEMBER` role is exercised only through tests.
    -   Logs are plain text with correlation IDs. JSON structured logs
        come with deployment (Milestone 8).
    -   Forwarded-header handling for the public HTTPS origin behind a
        load balancer is left to Milestone 8 (see D6).
    -   No rate limiting on sign-in endpoints yet (Milestone 8 or 11).

### Milestone 3: done (2026-10-15)

-   **Schema:** `V4__clients_and_sessions.sql` adds `clients` and
    `photo_sessions`. Both have a non-null `workspace_id`,
    `archived_at`, and `version`. A session's optional client uses a
    composite foreign key `(workspace_id, client_id)`, so the database
    itself refuses a link to another workspace's client. CHECK
    constraints enforce text lengths, an end after the start, and a
    start once a session is booked.
-   **Clients** (`clients` module): create, read, edit, archive, and
    restore. Lists are paged, sorted by name ignoring case, and
    searchable by part of the name or email (`LIKE` wildcards are
    escaped). Text is trimmed, and blank optional fields become null.
    Validation covers name length, email format, phone characters, and
    notes length.
-   **Sessions** (`sessions` module, which depends on `clients` through
    `ClientService`'s public methods only): create, read, edit,
    archive, restore, and status changes. The lifecycle is
    `INQUIRY → BOOKED → COMPLETED → DELIVERED`. `INQUIRY` and `BOOKED`
    can be cancelled, and `CANCELLED` can reopen as `INQUIRY`.
    `DELIVERED` is final. Booked and later statuses need a start
    time. New sessions start as inquiries, and only
    `POST …/status` changes status. Lists are paged, newest first
    with unscheduled sessions on top, and filterable by status and
    client. A client link must be an active client of the same
    workspace; a foreign client ID gets the same 400 as an unknown
    one.
-   **Time zones (D9):** start and end are entered as wall-clock time
    in the workspace zone and converted on the server. Times skipped
    by a daylight-saving change are rejected, and repeated times
    resolve to the earlier one. They are stored as UTC and returned
    as both UTC instants and workspace-local times.
-   **Conventions (D10):** archive instead of delete; optimistic
    locking with `version` (409 on a stale edit); paging with
    `page`/`size` (max 100) and `{items, page, size, totalItems}`.
    Malformed path IDs return 404 like unknown ones. Malformed or
    out-of-range query parameters return 400 with per-field `errors`.
    New `ConflictException` (409 with a fixed, safe `detail`) and
    `InvalidFieldsException` (400 with `errors`) in `shared/web`.
-   **Frontend:** Sessions, Clients, and Members tabs inside the
    workspace. Each resource has a list (filters, search, pager), a
    detail page (status actions from `allowedStatuses`,
    archive/restore, the client's sessions), and a create/edit form
    (client picker with server-side search, `datetime-local` inputs
    labelled with the workspace zone). Every page has loading, empty,
    not-found, and retryable error states, and 409 explanations are
    shown inline. Pages live in the URL hash (`#/clients/{id}`), via
    a 40-line `routing.ts` instead of a router dependency. Switching
    workspace returns to `#/sessions`. A shared `useLoad` hook
    handles abortable loading. No new dependencies.
-   **Decisions:** D9 (session time zones) and D10 (record lifecycle
    conventions).
-   **Verified locally on 2026-10-15:**
    -   `cd backend && ./mvnw verify`: 16 unit tests and 54
        integration tests passed.
        -   `SessionStatusTest`, `SessionScheduleTest`: the transition
            table; Warsaw summer/winter offsets, the spring-forward gap
            (2027-03-28 02:30 rejected), the fall-back overlap
            (2026-10-25 02:30 resolves to CEST), end-after-start, and
            year bounds.
        -   `ClientsIT` (11): CRUD and normalization, validation,
            client-supplied IDs and workspace ignored, stale version
            409, archive/restore (idempotent; editing archived is a
            409), paging and case-insensitive sort, search including
            a literal `%`, paging validation. Cross-workspace denial
            through both the foreign and the own workspace path, with
            no side effects. Unknown, foreign, and malformed IDs give
            the same 404. Members can manage clients.
        -   `PhotoSessionsIT` (16): workspace-zone conversion (checked
            in the stored UTC value), changing the workspace zone
            keeps the instant, validation and DST gap errors, offsets
            rejected, the full lifecycle with 409s for skipped and
            backward steps, booked sessions keep their start, stale
            version, client links (foreign = unknown, archived
            refused, an existing link kept), archive/restore, list
            order, filters, and paging. Cross-workspace denial for
            every endpoint. The composite foreign key rejects a direct
            SQL insert linking another workspace's client.
        -   `ApiRouteAuthorizationIT` covers the 12 new routes
            automatically.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 41 tests passed and the build succeeded.
        These include time formatting in the workspace zone (on a
        machine in America/Los_Angeles), route parsing, client and
        session flows against a mocked API (create, validation
        errors, nested `session.startLocal` errors, 409 conflicts,
        search and paging queries, status actions, archive/restore,
        not-found, retry), and workspace switching resetting the
        route.
    -   Manual run, `spring-boot:test-run -Dspring-boot.run.profiles=compose-db`
        plus `npm run dev`, all through the Vite proxy:
        -   The Compose database migrated from V3 to V4.
        -   Created a client and a session at 2026-11-07 10:30 Warsaw
            time. It was stored as 09:30 UTC (CET) and returned with
            `startLocal` 10:30.
        -   Booked the session; BOOKED → DELIVERED got a 409 with a
            correlation ID. Filtering by client worked.
        -   A second user got 404 for the session and for creating a
            client in the first workspace, 404 for the client through
            their own workspace, and a 400 `clientId` error when
            linking it.
        -   POST without the CSRF header got 403.
        -   The backend log contained no client names, emails, or
            session titles.
-   **Known gaps:**
    -   The UI was not clicked through in a real browser (none is
        available in this environment). Component tests cover the
        flows, and the API was exercised through the dev proxy.
        Worth a quick manual pass: README, "Clients and sessions".
    -   No hard delete or data export for clients yet; these belong
        to data-retention work (Milestone 11).
    -   List filters and the current page are not kept in the URL, so
        going back from a detail page returns to page one.
    -   Client search uses `ILIKE` without a trigram index. That is
        fine for one business; add an index if a workspace reaches
        many thousands of clients.
    -   No audit events for status changes yet. `audit` arrives with
        the first sensitive approvals; `POST …/status` is the single
        hook point.

### Milestone 4: done (2026-10-16)

-   **Schema:** `V5__photo_assets_and_jobs.sql` adds `photo_assets`
    and `background_jobs`. An asset's session uses a composite foreign
    key `(workspace_id, session_id)`. CHECK constraints require
    derivatives, checksum, and dimensions once `READY`, and a failure
    reason exactly when `REJECTED` or `FAILED`. Jobs have a unique
    idempotency key.
-   **Uploads** (`assets` module, D11):
    `POST …/sessions/{id}/assets` checks the session (archived → 409),
    type (JPEG/PNG), size (100 MB), file name, unfinished uploads (100
    per workspace), and storage quota (100 GB). Limits are checked
    under a per-workspace advisory lock. It records a `PENDING_UPLOAD`
    asset and returns a 15-minute presigned `PUT` for a
    server-generated key, with `Content-Type` and `Content-Length`
    signed. `POST …/assets/{id}/complete` checks the object exists,
    then moves the asset to `PROCESSING` and queues its job in the
    same transaction. It is idempotent. List and get return 10-minute
    presigned thumbnail and preview URLs for `READY` assets only. No
    URL for an original is ever returned.
-   **Processing** (`ProcessAssetJob`): checks the stored size against
    the declared one and computes SHA-256. It sniffs the type from
    magic bytes and refuses images over 200 MP from the header, before
    decoding. It decodes subsampled, applies EXIF orientation
    (`metadata-extractor`), and writes 2048 px and 480 px JPEGs
    without metadata to fixed keys. Bad files become `REJECTED` with
    a fixed reason, and their originals are deleted. When retries run
    out, the asset becomes `FAILED`.
-   **Jobs** (`jobs` module, D12): `JobService.enqueue` must run in the
    caller's transaction (outbox). A dispatcher sends due job IDs to
    SQS, and re-sends jobs that never ran. The worker claims with a
    conditional update and lease, and retries with exponential backoff
    and jitter stored on the row. After 5 attempts the job is `FAILED`
    and the handler's `giveUp` runs. Errors are stored as class names
    or fixed sentences. SQS redrive moves crash-looping messages to a
    DLQ. Worker logs carry the correlation ID of the request that
    queued the job. Dispatcher and worker are toggled by config.
-   **Cleanup:** an hourly task deletes uploads still pending after 24
    hours, object first, then row.
-   **Storage adapter** (`storage` module): `ObjectStorage` with an S3
    implementation. AWS SDK types stay in the `storage` and `jobs`
    adapters. All S3/SQS clients have connection, socket, and call
    timeouts.
-   **Infrastructure** (`infra/`, D13): the first CDK stack,
    `PhotoSpace-<env>-Storage`. It has a private bucket (block public
    access, ACLs off, TLS-only policy, SSE-S3, versioning with 30-day
    expiry of old versions, CORS `PUT` from the app origin only,
    retained on delete) and the job queue with its DLQ. It runs on
    Node 24 type stripping, with recommended feature flags in
    `cdk.json`. CI gets an infra job (typecheck, test, synth).
-   **Local and test stack:** `compose.yaml` adds S3Mock and ElasticMQ
    (`local/elasticmq.conf` defines the queue, DLQ, and redrive).
    Testcontainers starts the same images for integration tests and
    `spring-boot:test-run`.
-   **Frontend:** a Photos section on the session page. It has
    multi-file upload (3 in parallel), a client-side type/size check,
    per-file progress and errors (400/409 reasons, storage failures),
    a thumbnail grid with status badges and rejection reasons, and
    previews in a new tab. It polls every 2 s while photos are
    processing. Uploads are hidden for archived sessions. No new
    dependencies.
-   **New dependencies:** AWS SDK v2 `s3`, `sqs`, `apache5-client`
    (2.55.10), `metadata-extractor` 2.19.0; `aws-cdk-lib` 2.272.0,
    `constructs`, `aws-cdk` CLI (infra only).
-   **Decisions:** D11 (presigned uploads and processing), D12 (job
    table outbox plus SQS), D13 (local emulators, CDK testing).
-   **Verified locally on 2026-10-16:**
    -   `cd backend && ./mvnw verify`: 30 unit tests and 79 integration
        tests passed.
        -   `ImageProcessorTest` (8): JPEG and PNG derivatives and
            sizes, no upscaling, transparency on white, EXIF
            orientations 6 and 3, non-images, truncated JPEG, and a
            900 MP PNG header refused without decoding. Also
            `FileNamesTest`, `BackoffTest`, and `SqsJobQueueTest`.
        -   `AssetsIT` (16): the full upload through a presigned URL to
            S3Mock, completion, the real worker, and `READY` with
            upright dimensions, checksum, and fetchable thumbnail and
            preview. Also: idempotent completion (one job), and
            reprocessing after a simulated crash (one asset, same
            keys). A JPEG declared as PNG is recorded as JPEG. Text
            uploaded as JPEG is `REJECTED` with its original deleted,
            and a size mismatch is `REJECTED`. Validation errors,
            completion before upload (409), and archived sessions (409)
            are covered. So are the quota (including freed space) and
            the pending-upload limit, members uploading, and cleanup of
            abandoned uploads (idempotent). Cross-workspace denial
            covers every route via the foreign and the own workspace
            path, with no side effects. The composite FK rejects a
            cross-workspace session.
        -   `JobsIT` (9), through the real dispatcher, ElasticMQ, and
            worker: a commit runs the job, and retries succeed on
            attempt 3. Giving up after 3 attempts (in tests) stores
            only the error class. Idempotent enqueue, a required
            transaction with rollback, duplicate messages ignored,
            recovery of a job whose worker died, and failure of one
            that died on its last attempt are covered.
        -   `ApiRouteAuthorizationIT` covers the 4 new routes.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 49 tests passed (8 new in `Photos.test.tsx`)
        and the build succeeded.
    -   `cd infra && npm ci && npm run typecheck && npm test && npx cdk
        synth -q -c env=staging -c appOrigin=https://staging.example.com`:
        7 tests passed and synth succeeded.
    -   GitHub Actions workflow checked with `actionlint`.
    -   Manual run (`docker compose up -d --wait`,
        `spring-boot:test-run -Dspring-boot.run.profiles=compose-db`,
        `npm run dev`, all API calls through the Vite proxy):
        -   V5 migrated on the Compose database.
        -   A 3000×2000 PNG went through the S3Mock CORS preflight
            from `http://localhost:5173` and the presigned `PUT`. It
            was `READY` about 2 s after completion, with a matching
            SHA-256 and a fetchable JPEG thumbnail.
        -   A text file declared as JPEG was `REJECTED`. A GIF got a
            400 and a missing CSRF header a 403. A second user got 404
            for the asset, the list, and starting an upload.
        -   Photos survive an S3Mock restart (named volume).
        -   The backend log had no presigned URLs, signatures, file
            names, session titles, or emails. Worker log lines carried
            the completing request's correlation ID.
-   **Known gaps:**
    -   Not deployed. No AWS account was used, so real S3 signature
        enforcement of type and size, bucket CORS, and block public
        access are verified only by CDK assertions until Milestone 8.
        S3Mock does not check signatures. Locally the worker's size
        and content checks are what enforce the limits.
    -   The upload UI was not clicked through in a real browser. Component
        tests and a scripted run through the proxy cover it. Worth a
        manual pass: README, "Photo uploads: a quick manual check".
    -   No IAM roles or grants yet. They come with Milestone 8's ECS
        services, and so do metrics and alarms for queue depth, DLQ
        messages, and stuck jobs.
    -   No malware scanning. Accepted files are re-encoded for
        derivatives, and originals are only ever served back as
        downloads (Milestone 5).
    -   Only JPEG and PNG. No HEIC, RAW, or WebP. CMYK JPEGs are
        rejected as unreadable.
    -   No delete or retry of an asset by the user. A `FAILED` photo is
        re-uploaded. Deletion belongs with galleries and retention.
    -   Thumbnail URLs expire after 10 minutes. A page left open
        longer needs a reload to show images again.
    -   `npm audit` reports a high-severity `brace-expansion` advisory
        bundled inside `aws-cdk-lib`. It is only used by `cdk synth`
        on developer and CI machines and is not deployed.
    -   Limits are global configuration, not per-workspace plans
        (Milestone 11).

### Milestone 5: done (2026-10-16)

-   **Schema:** `V6__galleries.sql` adds `galleries`, `gallery_assets`,
    `gallery_share_links`, and `gallery_access_events`. Composite
    foreign keys on `(workspace_id, session_id, id)` make every gallery
    photo a photo of the gallery's own session and workspace. Deleting
    a photo removes it from galleries. A link stores only the SHA-256
    of its token, and its expiry is required. An event records only
    link, kind, photo, and time.
-   **Gallery management** (`galleries` module, D14), under
    `/api/workspaces/{id}/galleries`: list (by session, archived),
    create (a draft for a non-archived session), get with ordered
    photos, settings `PUT` (title and downloads `NONE`, `PREVIEWS`, or
    `ORIGINALS`, with a version), `PUT …/photos` (the whole ordered
    list, ready photos of the session only, at most 500, with a
    version), status (`DRAFT` or `PUBLISHED`; an empty gallery can't be
    published and a published one can't be emptied), and archive and
    restore. Follows D10.
-   **Share links:** `POST …/links {validDays}` (1–365, as 24-hour
    periods) returns the 43-character token once. List shows the state
    (`ACTIVE`, `EXPIRED`, `REVOKED`), views, downloads, and last use.
    Revoke is idempotent. Links are never deleted.
-   **Visitor API:** `GET /api/public/gallery` and `POST
    /api/public/gallery/photos/{assetId}/download`, with the token in
    `X-Gallery-Token`. Anything but a live link to a published,
    unarchived gallery gets the same 404, and so does a photo outside
    the gallery. Downloads follow the gallery setting (403 when none
    are allowed). Download URLs are valid for 5 minutes and sign
    `Content-Disposition: attachment` with the uploaded name (an
    RFC 8187 UTF-8 name plus an ASCII fallback). The response holds
    no workspace, session, or link IDs and no file names. Views and
    downloads are recorded per link.
-   **Assets and storage:** `AssetService.findReady` and `downloadUrl`
    for galleries. `ObjectStorage.presignAttachment` signs the response
    disposition and type. `WorkspaceService.name` gives the studio name
    for visitors.
-   **Frontend:** a Galleries tab and a Galleries section on each
    session (create with title and downloads). The gallery page has
    publish and unpublish, archive and restore, settings, the photos in
    order (earlier, later, remove), a picker for the session's ready
    photos (add one, add all), and share links: create with a validity,
    the URL shown once with Copy, usage, and revoke. The visitor page
    `#/g/<token>` skips sign-in and every workspace call. It shows the
    studio, the expiry, thumbnails, previews, and download buttons when
    allowed, and a plain message for a link that doesn't work. No new
    dependencies.
-   **Docs:** D14. ARCHITECTURE §6 (gallery tables) and §8 (retention
    and deletion of gallery data).
-   **Verified locally on 2026-10-16:**
    -   `cd backend && ./mvnw verify`: 37 unit tests and 88 integration
        tests passed.
        -   `GalleriesIT` (9): the full flow with real uploads, worker,
            and S3Mock: create, order, publish, and a link. The stored
            value is the SHA-256 of the token, and the token is never
            in a list. The visitor sees the photos in order, with no
            IDs or file names outside presigned URLs and no session
            cookie. The original downloads byte for byte with the
            signed `Content-Disposition`, and the view and download are
            recorded. Download settings: `NONE` gets 403 and is not
            recorded; `PREVIEWS` and `ORIGINALS` sign the right key and
            name. Unpublished, archived, expired, and revoked links get
            404, and work again after republishing or restoring.
            Revoking is idempotent. Missing, empty, malformed, unknown,
            SQL-ish, and hash-as-token values get the same 404 and
            record nothing. A token can't download a photo from
            another gallery or workspace, and gets 401 on `/api/me` and
            every workspace route. Photos must be ready, of the
            session, and not duplicated; versions are checked; and the
            database refuses another session's or workspace's photo.
            Validation covers sessions, archived sessions and
            galleries, titles, downloads, and `validDays`. Lists filter
            by session and archive state. Cross-workspace denial covers
            every route through both the foreign and the own workspace
            path, with no side effects.
        -   `ShareTokensTest` (3), `ContentDispositionsTest` (3: plain,
            non-ASCII, quote injection), `FileNamesTest` (preview
            names). `ApiRouteAuthorizationIT` allow-lists the 2 visitor
            routes; the 9 gallery routes are workspace-scoped.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 60 tests passed (11 new in
        `Galleries.test.tsx`) and the build succeeded.
    -   Manual run (`docker compose up -d --wait`,
        `spring-boot:test-run -Dspring-boot.run.profiles=compose-db`,
        a Python script against `:8080`, and the Vite proxy):
        -   V6 migrated on the existing Compose database (with
            Milestone 4 data).
        -   Two uploaded PNGs, a gallery, a reorder, publish, and a
            30-day link. The visitor got the gallery (`Cache-Control:
            no-store`), only an `XSRF-TOKEN` cookie, and an original
            download with `filename*=UTF-8''%C5%81%C3%B3d%C5%BA%201.png`.
            The visitor got 401 on `/api/me` and, after revoking, 404
            (also through the Vite proxy). The link list showed 1 view
            and 1 download. A second user got 404 for the gallery.
        -   The backend log had no share token, no presigned
            signatures, no file names, and no emails.
-   **Known gaps:**
    -   Not deployed. Real S3 enforcement of signed response headers
        and block public access is still unverified on AWS (Milestone
        8). S3Mock honored `response-content-disposition`.
    -   The gallery pages were not clicked through in a real browser.
        Component tests and the scripted run cover them. Worth a
        manual pass: README, "Galleries: a quick manual check".
    -   No rate limiting on the visitor API. Tokens can't be guessed,
        but each visit writes an event row. WAF and throttling come
        with Milestone 8.
    -   Presigned URLs already handed out keep working for up to 10
        minutes (previews) or 5 minutes (downloads) after a link is
        revoked.
    -   A visitor page open longer than 10 minutes needs a reload for
        fresh images, and each reload counts as a view.
    -   One session per gallery, one download setting per gallery, no
        zip of a whole gallery, no favourites or client selections, and
        no email sending of links (the photographer copies the URL).
    -   Photo deletion is not built. The retention rules for it are
        documented in ARCHITECTURE §8.

### UI styling pass: done (2026-10-16)

Not a numbered milestone: a cross-cutting restyle before Milestone 6,
with no behavior, API, or routing changes. Decision: D15.

-   Tailwind CSS v4 and shadcn/ui (button, input, textarea,
    native-select, badge, alert, sheet) with HoneyBook-inspired
    tokens in `frontend/src/index.css`, OS-driven dark mode, and
    self-hosted Inter.
-   Signed-in layout `src/layout/AppShell.tsx`: sidebar with the
    workspace switcher, section navigation, and sign-out from `lg` up;
    a top bar with a slide-in menu below. A skip-to-content link, and
    the system status in the footer.
-   Centered card layout for sign-in, first workspace, and loading.
    Visitor gallery page with its own header and a full-width photo
    grid.
-   Shared pieces in `src/ui/`: page header, card panels, divided
    record lists with whole-row links, labelled fields, status pills
    with tones, loading/error/empty states, and photo tiles.
-   Tests: `src/test/workspace.tsx` passes the new `Workspaces` props,
    and the onboarding test in `App.test.tsx` checks the members list
    (name, "Owner" badge) instead of the old "name (owner)" text.
-   Verified: `cd frontend && npm run lint && npm run typecheck &&
    npm test && npm run build`: 60 tests passed, lint clean, build
    succeeded (JS about 116 kB gzipped, was about 81 kB).
-   Not verified: no browser or backend was available where this was
    built, so the layouts were not checked visually. Worth a manual
    pass at 375, 768, and 1280 px wide, in light and dark: sign-in,
    first workspace, sessions, clients, galleries, a session with
    photos, a gallery, and a visitor link.

### Milestone 6: done (2026-10-16)

-   **Schema:** `V7__content_studio.sql` adds `brand_profiles` (one per
    workspace, versioned), `content_batches` (input, status, run count,
    safe failure reason, provider, model, prompt version, token counts,
    suggestions), `content_batch_assets` (selection order and carousel
    position, composite foreign keys to the session's photos),
    `content_drafts` (status, current version, approved version,
    approver, time), and `content_draft_versions` (immutable, with
    source `GENERATED` or `EDITED` and editor).
-   **Provider boundary** (`content` module, D16):
    `ContentGenerationProvider` with typed `GenerationRequest` /
    `GenerationResult` and a provider-neutral
    `ContentGenerationException` kind. `app.content.provider` picks
    `mock` (default; deterministic, no network) or `anthropic`
    (`content.anthropic`, official Java SDK 2.65.0, `claude-opus-5-5`,
    effort `medium`, JSON schema output, server-side refusal
    fallbacks, 120 s timeout). SDK types stay in the adapter. The
    backend refuses to start with `anthropic` and no key.
-   **Generation flow:** `POST …/content/batches` validates the session
    (not archived) and 1–10 ready photos of it, stores the batch and
    queues `generate-content` in one transaction. The job sends the
    selected photos' metadata-free previews, the typed notes, the
    session type, and the brand profile (never client details, title,
    or location), validates the answer (`ContentValidator`), and stores
    drafts in the transaction that ends the batch. Temporary failures
    (rate limits, timeouts, unusable answers) retry with the job
    backoff and show the last problem; permanent ones (refusal,
    rejected request, bad credentials) fail at once. Users can retry a
    failed batch or regenerate any batch as a new one.
-   **Safeguards:** a versioned prompt (`instagram-drafts-v1`) with the
    photographer's text escaped inside tags and marked as data;
    instructions not to invent names, places, dates, prices, or
    promises; model warnings for missing information; deterministic
    review hints (`ContentChecks`) for handles, links, emails, phone
    numbers, and prices not in the notes or brand profile; and
    approval of an exact version, reset by any later edit. Nothing is
    published: the UI copies only approved, saved drafts.
-   **Frontend:** a **Content** tab (brand profile and all batches), an
    **Instagram drafts** panel on each session (photo picker in pick
    order, notes prefilled from the session, caption count, hashtag and
    call-to-action options, and a disclosure of what is sent), and a
    batch page that polls while generating, shows failures with Retry,
    and lists drafts with edit, save, approve, withdraw, copy, history
    (with "Use this text"), review hints, warnings, the suggested
    carousel order, and Story ideas. No new frontend dependencies.
-   **Docs:** D16; ARCHITECTURE §6 (content tables) and §10; README
    (API, content rules, manual check); `.env.example`.
-   **Verified locally on 2026-10-16:**
    -   `cd backend && ./mvnw verify`: 58 unit tests and 96 integration
        tests passed.
        -   `ContentIT` (8): what the provider receives (previews in
            selection order, brand profile, session type, typed notes;
            no client name, email, phone, title, location, or session
            notes); drafts persist; versioned edits, stale-version 409s,
            approval of the reviewed version only, re-approval after an
            edit, idempotent saves and approvals, history; field
            validation; review hints against the brand profile;
            temporary failure retried, permanent failure not retried,
            manual retry (`run` 2), exhausted retries keep the last
            problem; regenerate; request validation (empty, duplicate,
            11 photos, other session's, not ready, unknown session,
            caption count, notes length, missing options, archived
            session); brand profile locking and limits; cross-workspace
            denial on every content route through both workspace paths,
            with no side effects.
        -   `AnthropicContentProviderTest` (12) against a local stub of
            the Messages API: the request (model, effort, schema,
            fallbacks, image blocks with the exact bytes, escaped data,
            API key header), parsing and token counts, and the mapping
            of 429, 401, 403, 404, 400, 413, 500, 529, refusal,
            `max_tokens`, malformed text, and a timeout, without the
            provider's message.
        -   `ContentValidatorTest` (6), `ContentChecksTest` (2),
            `ContentPromptTest` (1). `BackendApplicationIT` expects V7.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 70 tests passed (10 new in `Content.test.tsx`),
        lint clean, build succeeded (JS about 122 kB gzipped).
    -   Browser run in Chrome (Compose services, `spring-boot:test-run
        -Dspring-boot.run.profiles=compose-db`, Vite on :5173): V7
        migrated on the existing database. Saved a brand profile;
        created drafts from two photos of a session (notes prefilled,
        pick order shown, mock disclosure); the batch page went from
        "Writing drafts…" to three drafts by polling. Edited a draft
        (version 2, review hint for "@anna.nowak"), approved it, copied
        it, saw both versions in History, reloaded (state kept), edited
        it again (back to draft, version 3), and regenerated (new batch,
        same photos and notes). Restarted with `CONTENT_PROVIDER=
        anthropic` and a dummy key: the form showed the real-provider
        disclosure, the real Claude API answered 401, and the batch
        failed with "AI generation is not set up correctly…" at once.
        Restarted with the mock provider and **Retry** produced drafts
        (`run` 2). A session of another workspace showed "not found",
        and a second dev user got 404 for the batch, brand profile, and
        list. No browser console errors. The backend log had no key,
        notes, or file names.
-   **Known gaps:**
    -   No real Claude generation was run (no API key here). Only the
        401 path reached the real API. Caption quality, latency, and
        token cost with real photos are unverified.
    -   Batches and drafts cannot be deleted or archived yet, and there
        is no per-workspace usage limit or cost report beyond stored
        token counts.
    -   The job log says "succeeded" for a batch that ended in a
        permanent failure (the job ran to completion; the batch
        failed), as with rejected uploads.
    -   Review hints use simple patterns: they can miss invented
        details (names, dates) and flag harmless numbers.
    -   No audit log of approvals yet beyond `approved_by` and
        `approved_at` (the `audit` module is not built).

### Milestone 7: done (2026-10-16)

-   **Schema:** `V8__tasks_automation_audit.sql` adds `automation_rules`
    (one kind: trigger status, task title, days until due, enabled),
    `automation_runs` (unique per rule and session; task title, due
    date, and client fixed when the rule fires; status, run count, safe
    failure reason), `tasks` (composite foreign keys to the client,
    session, and member; unique `automation_run_id`), and
    `audit_events`.
-   **Tasks** (`tasks` module): CRUD with optimistic locking, an
    idempotent `…/status` (`OPEN`, `DONE`), delete, and filters by
    status, client, session, assignee, and overdue (computed in the
    workspace time zone). Linked clients, sessions, and assignees must
    belong to the workspace; archived ones cannot be newly linked.
-   **Automation** (`automation` module, D17): a session status change
    publishes `SessionStatusChanged` in its transaction; the listener
    inserts one run per matching enabled rule and queues a
    `run-automation` job through the existing job outbox (D12). The job
    creates the task once (`ON CONFLICT` on the run) and ends the run.
    After 5 failed attempts the run is `FAILED`; members can retry it.
    Owners create and change rules. No AI is involved.
-   **Failed jobs:** `GET …/jobs` (owners, read-only) lists background
    jobs with attempts and safe error summaries. Runs show their job's
    attempt count and last error while pending.
-   **Audit** (`audit` module): `AuditService.record` in the caller's
    transaction for session status changes, task changes, rule changes,
    run outcomes and retries, gallery publish and unpublish, share link
    creation and revocation, and content draft approval and withdrawal
    (closing the Milestone 6 gap). `GET …/audit-events` is for owners.
-   **Frontend:** a **Tasks** page (Open/Done/All, assignee, overdue
    filters; checkbox to complete), new and edit task pages (due date,
    assignee, client picker, session link, two-step delete), a **Tasks**
    panel on each session (reloads 4 seconds after a status change to
    pick up rule tasks), an **Automation** page (rules, recent runs with
    Retry and an "Open task" link, failed background jobs for owners),
    and an **Activity** page for owners. No new dependencies.
-   **Docs:** D17; ARCHITECTURE §6 (tasks, automation, audit tables);
    README (API, rules, manual check).
-   **Verified locally on 2026-10-16:**
    -   `cd backend && ./mvnw verify`: 58 unit tests and 106 integration
        tests passed.
        -   `AutomationIT` (5): a COMPLETED rule creates one task, linked
            to the session and client, due in the workspace time zone; a
            repeated status change, a duplicate `SessionStatusChanged`
            event, and a re-delivered job (attempt 2) leave one run and
            one task; disabled rules and other statuses do not fire, and
            cancelling and re-booking does not fire a rule twice; with
            task inserts failing (a test trigger), the run fails after 3
            attempts with a safe reason, the job is listed as failed
            with a class-name error, retry (`run` 2) creates the task,
            and a second retry gets 409; system events have no actor;
            rule validation, optimistic locking, owner-only writes,
            owner-only jobs and audit log; cross-workspace denial on
            rules, runs, retry, jobs, and audit events, and another
            workspace's status changes never fire these rules.
        -   `TasksIT` (5): CRUD, ignored client-sent status and source,
            stale-version 409, idempotent completion, delete, audit
            trail per action; filters and ordering, overdue in
            Europe/Warsaw, done tasks never overdue; validation of
            fields and of foreign or archived links; members manage
            tasks, and removing a member unassigns them; cross-workspace
            denial on every route with no side effects.
        -   `GalleriesIT` and `ContentIT` now check the audit events of
            publishing, share links (no token in any event), and draft
            approvals. `BackendApplicationIT` expects V8.
    -   `cd frontend && npm run lint && npm run typecheck && npm test &&
        npm run build`: 80 tests passed (6 new in `Tasks.test.tsx`, 4 in
        `Automation.test.tsx`, new routes in `routing.test.ts`), lint
        clean, build succeeded (JS about 126 kB gzipped).
    -   Browser run in Chrome (Compose services, `spring-boot:test-run
        -Dspring-boot.run.profiles=compose-db`, Vite on :5173, the
        browser still signed in from an earlier Google sign-in): V8
        migrated on the existing database. In a new workspace
        (America/Los_Angeles), added a "Booked → Send the contract, due
        2 days" rule, created a session with a start time, and marked
        it booked. The task appeared on **Tasks** as "Due Oct 18, 2026 ·
        M7 test session" with **Automated**, and the run showed "Task
        created" with an "Open task" link. Completed the task from the
        session panel. **Activity** listed the rule, the status change,
        the task created by "Automation", the run, and the completion.
        No browser console errors. The backend log had IDs only.
    -   Found and fixed during the browser run: the session's task panel
        reloaded before the job had created the task. It now reloads
        again 4 seconds later (covered by a test).
-   **Known gaps:**
    -   The failure and retry path was verified by `AutomationIT` only,
        not in the browser.
    -   Only one rule kind, firing once per session. No rules for other
        events (gallery published, date reached), and no scheduled
        reminders for due dates.
    -   Tasks have no comments, attachments, recurrence, or
        notifications. Assignment is limited to existing members, and
        inviting members is still not built.
    -   The audit log has no retention policy, export, or filters in the
        UI beyond paging. Workspace creation, client, and session field
        edits are not audited.
    -   The job log says "succeeded" for jobs whose feature recorded a
        permanent failure (unchanged from Milestones 4 and 6).

### Milestone 8: next

AWS deployment and operational hardening.
