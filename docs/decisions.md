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

## D11: Photo uploads go straight to private S3 through presigned PUT URLs

- **Date:** 2026-10-16 (Milestone 4)
- **Decision:**
  - The browser asks the API to start an upload (`POST …/sessions/{id}/assets` with name, type, and size). The API checks membership, the session (it must not be archived), the type allowlist (JPEG and PNG), the size limit (100 MB), the number of unfinished uploads (100 per workspace), and the workspace storage quota (100 GB, counting pending, processing, and ready originals). It records a `PENDING_UPLOAD` asset and returns a presigned `PUT` URL, valid for 15 minutes, for a key it generates: `workspaces/{workspaceId}/originals/{assetId}`. `Content-Type` and `Content-Length` are signed, so S3 refuses any other type or size.
  - The browser then calls `POST …/assets/{id}/complete`. The API checks that the object exists, moves the asset to `PROCESSING`, and queues the processing job in the same transaction. Completing again is harmless. There is no S3 event path; completion is the only trigger.
  - The worker trusts nothing the browser said. It checks the stored size, identifies the type from the file's first bytes, reads the pixel dimensions from the header and refuses images over 200 megapixels before decoding, decodes subsampled, applies the EXIF orientation, and writes a 2048 px preview and a 480 px thumbnail as re-encoded JPEGs without metadata, so GPS and camera data stay out of them. It records the upright dimensions and the SHA-256 of the original. A file that is not an acceptable photo becomes `REJECTED` with a fixed reason, and its original is deleted.
  - Only `READY` assets get URLs, and only for derivatives: presigned `GET` URLs valid for 10 minutes. The API never returns a URL for an original (downloads are Milestone 5). File names are display-only, cleaned of directories and control or formatting characters, and never used in keys.
  - Uploads still pending after 24 hours are deleted, object first, by an hourly cleanup.
  - Storage sits behind `ObjectStorage` (`storage` module). AWS SDK types stay inside its S3 adapter.
- **Why:** direct uploads keep large files out of the API process. Signing the type and size makes S3 enforce the limits, and server-side validation covers everything a signature cannot (content, dimensions, orientation).
- **Consequences:** the bucket needs a CORS rule for the app origin (`PUT` with `content-type` only). Limits are configuration (`app.assets.*`), not per-workspace plans yet. Retrying a `FAILED` asset means uploading it again. Formats beyond JPEG and PNG (HEIC, RAW, WebP) need decoders the JDK lacks, so they are left for later.

## D12: Background jobs: a job table as outbox, SQS as the wake-up signal

- **Date:** 2026-10-16 (Milestone 4)
- **Decision:**
  - `background_jobs` is the source of truth: type, subject, a unique idempotency key, status (`QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`), attempts, the next attempt time, a lease, a safe error summary, and the correlation ID of the request that queued it. `JobService.enqueue` requires the caller's transaction, so the job commits or rolls back with the change that needs it.
  - A dispatcher, scheduled every 2 seconds, sends the IDs of due jobs to SQS (`FOR UPDATE SKIP LOCKED`, so several instances can run it). It re-sends a job that was sent over 15 minutes ago but never ran (a lost message, or a worker that died mid-job).
  - The worker long-polls SQS. It claims the job with one conditional `UPDATE` (queued and due, or running on an expired lease), runs the handler, and records the result. A failure schedules the next attempt on the row with exponential backoff and jitter (30 s base, 15 min cap). After 5 attempts the job is `FAILED` and the handler's `giveUp` records the final outcome. The message is deleted in every case. Error summaries are the exception class or a fixed `JobFailure` sentence, never exception messages.
  - SQS's redrive policy moves a message to the dead-letter queue after 5 receives that were never deleted. That only happens when processing a message keeps crashing the worker or the database is unreachable. The job row stays unfinished, so the dispatcher re-sends it later anyway. Re-driving the DLQ into the main queue is always safe.
  - Handlers must be idempotent. The photo handler acts only on an asset still in `PROCESSING`, writes derivatives to fixed keys, and never inserts asset rows.
  - One jar runs both roles: `app.jobs.dispatcher.enabled` and `app.jobs.worker.enabled` (both on locally). Milestone 8 runs the worker as its own ECS service.
- **Why:** the database row makes status visible to the UI and keeps retries and backoff testable without relying on queue features. SQS gives the worker a cheap, scalable wake-up and a DLQ. The outbox avoids the dual write where the database commits but the message is never sent.
- **Consequences:** dispatching adds up to 2 seconds of latency. There is no job-status API yet; the asset status is what users see. Metrics and alarms for queue depth, DLQ messages, and stuck jobs arrive with Milestone 8.

## D13: Local AWS emulators and infrastructure tests

- **Date:** 2026-10-16 (Milestone 4)
- **Decision:**
  - `compose.yaml` adds Adobe S3Mock (`adobe/s3mock`) for S3 and ElasticMQ (`softwaremill/elasticmq-native`) for SQS, both bound to `127.0.0.1`. ElasticMQ's queues, including the DLQ and its redrive policy, are in `local/elasticmq.conf`. The backend integration tests start the same images with Testcontainers. When an emulator endpoint is configured, the backend uses path-style URLs and placeholder credentials. Otherwise it uses the default AWS credential chain.
  - LocalStack and MinIO are not used: LocalStack now requires an account token, and MinIO no longer publishes images.
  - The CDK app (`infra/`) runs on Node 24's built-in TypeScript support, with no `ts-node` or build step, and is tested with `node --test` and `aws-cdk-lib/assertions` against the same feature flags as `cdk synth` (`cdk.json`).
- **Consequences:** S3Mock does not check signatures, so locally the signed type and size are not enforced. The worker's size and content checks still are. Real S3 behavior (signature enforcement, CORS, block public access) is verified only when Milestone 8 deploys to AWS.

## D14: Private galleries and share links

- **Date:** 2026-10-16 (Milestone 5)
- **Decision:**
  - A gallery is an ordered selection of the `READY` photos of **one session**. The service checks this, and so does the database, through composite foreign keys on `(workspace_id, session_id, id)`. Galleries never delete photos.
  - Settings are per gallery: `downloads` is `NONE` (view only), `PREVIEWS` (the 2048 px JPEG), or `ORIGINALS`. There is no per-photo policy. A gallery is `DRAFT` or `PUBLISHED`, and follows D10: optimistic locking, archive and restore, and a status endpoint. An empty gallery cannot be published, and a published one cannot be emptied. `PUT …/photos` replaces the whole ordered list in one call, up to 500 photos.
  - **Share links:** 32 random bytes from `SecureRandom`, base64url (43 characters). Only the SHA-256 is stored. A fast hash is enough for a 256-bit random secret, and it allows an indexed lookup. The token is returned once, when the link is created; a lost link is replaced, not shown again. Every link expires (1–365 days, counted as 24-hour periods so daylight-saving changes don't shift it) and can be revoked. Links are revoked, never deleted.
  - **Visitor API:** `GET /api/public/gallery` and `POST /api/public/gallery/photos/{assetId}/download` are the only unauthenticated data routes, and they are allow-listed in `ApiRouteAuthorizationIT`. The token travels in the `X-Gallery-Token` header, never in a URL. The SPA link is `#/g/<token>`: browsers do not send fragments, so the token stays out of request lines, access logs, and `Referer` headers. A token opens a gallery only while the link is unexpired and unrevoked and the gallery is published and not archived. A missing, malformed, unknown, expired, or revoked token, an unpublished or archived gallery, and a photo outside the gallery all get the same 404. Downloads when the gallery allows none get 403. Visitors get no session; CSRF still applies to the download `POST`.
  - Visitors get presigned `GET` URLs: previews and thumbnails for 10 minutes, downloads for 5 minutes. Download URLs sign `response-content-disposition` (attachment, the uploaded file name with an ASCII fallback and an RFC 8187 UTF-8 name) and `response-content-type`. The visitor response contains no workspace, session, or link IDs and no file names. The object keys inside presigned URLs do contain the workspace and asset IDs, and those grant nothing without membership.
  - Each gallery open and each download is recorded in `gallery_access_events` with the link, the photo, and the time only. The link list shows views, downloads, and last use per link.
- **Why:** tying a gallery to one session matches how photographers deliver, and keeps the picker and the database rules simple. Header-plus-fragment transport keeps a long-lived credential out of every log the platform or a CDN would write.
- **Consequences:** a gallery cannot mix photos from several sessions yet. Relaxing that means dropping the session from the foreign keys. A visitor page left open longer than 10 minutes needs a reload for fresh image URLs, and each reload counts as a view. There is no zip download of a whole gallery yet. There is no rate limit on the visitor API: tokens can't be guessed, and request throttling (WAF) comes with Milestone 8.
