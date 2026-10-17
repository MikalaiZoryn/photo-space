# Photography Business SaaS --- Architecture

## 1. Architecture decision summary

Start with a **modular monolith** in a monorepo. Keep a single Spring
Boot backend codebase with clear module boundaries, a React/TypeScript
frontend, PostgreSQL for business data, S3 for photo files, and
SQS-backed asynchronous processing. Deploy the API and long-running
workers as ECS Fargate services. Use Lambda selectively for short event
handlers or scheduled jobs.

Do not begin with multiple microservices. Module boundaries should make
future extraction possible only when operational scale or team ownership
justifies it.

## 2. High-level system

``` text
Browser
  |
  | HTTPS, secure application session
  v
React + TypeScript frontend
  |
  | authenticated API requests
  v
Spring Boot API (ECS Fargate)
  |          |                |
  |          |                +--> Google APIs through integration adapters
  |          |
  |          +--> PostgreSQL (RDS)
  |
  +--> S3 (private photo objects; uploads/downloads via short-lived presigned URLs)
  |
  +--> SQS --> Worker process (ECS Fargate)
                 |--> image processing
                 |--> AI content generation
                 |--> integrations and workflow jobs

CloudWatch: logs, metrics, alarms
Secrets Manager / SSM: credentials and configuration
GitHub Actions: tests, build, deploy
```

All external provider calls should be made through backend services or
workers. The browser must not receive long-lived provider credentials,
model API keys, or cloud credentials.

## 3. Repository layout

Use a single repository with clear, documented boundaries. Adjust names
if the repository already has conventions, but do not add frameworks or
tooling without a concrete need.

``` text
/
  CLAUDE.md
  SYSTEM_OVERVIEW.md
  ARCHITECTURE.md
  IMPLEMENTATION_PLAN.md
  README.md
  frontend/
  backend/
  infra/
  docs/
```

Suggested backend packages/modules:

``` text
identity/
workspaces/
clients/
sessions/
assets/
galleries/
content/
tasks/
calendar/
mailbox/
automation/
integrations/
jobs/
audit/
shared/
```

Keep dependencies pointed inward toward domain concepts. Avoid circular
module dependencies. Do not let Google, AWS, or a specific LLM
provider's SDK types leak into domain models.

## 4. Backend module responsibilities

-   **identity:** authentication callback/session lifecycle, external
    identity mapping.
-   **workspaces:** workspace membership and authorization policies.
-   **clients:** client profile and contact information.
-   **sessions:** photography session lifecycle and relationships.
-   **assets:** metadata, upload authorization, processing state,
    derivatives.
-   **galleries:** gallery configuration, share tokens, access rules,
    download events.
-   **content:** brand profile, AI generation requests, draft versions,
    approvals.
-   **tasks:** task creation, assignment, due dates, status.
-   **calendar:** internal calendar model and future provider
    synchronization.
-   **mailbox:** Gmail connection, sync state, thread/message metadata,
    draft workflows.
-   **automation:** explicit rules and orchestration of approved
    workflows.
-   **integrations:** provider adapters and token lifecycle.
-   **jobs:** durable job state, dispatch, retries, idempotency.
-   **audit:** records of sensitive changes and actions.
-   **shared:** only genuinely shared primitives; avoid turning this
    into a dumping ground.

Each module should expose a small application-service/API boundary. A
module may have its own package structure for API, application, domain,
and infrastructure when that improves clarity; avoid excessive ceremony
for simple CRUD.

## 5. Multi-tenancy and authorization

Use a workspace-based tenant model.

-   `users` represent application identities.
-   `workspaces` represent photography businesses.
-   `workspace_members` link users to workspaces and carry a role.
-   Every workspace-owned business record has a non-null `workspace_id`.
-   Every API operation must authorize the active user against the
    record's workspace.
-   Never trust a `workspace_id` supplied by the client without checking
    membership and record ownership.
-   Queries and updates must be scoped by workspace; use authorization
    tests to prevent insecure direct object references.
-   Cross-workspace IDs should not reveal whether a record exists.
    Return a consistent not-found/forbidden behavior according to the
    API policy.
-   Use database constraints and indexes to support tenant-scoped access
    patterns.

Consider PostgreSQL row-level security only if the team can test and
operate it correctly. Application-level tenant authorization is
mandatory regardless.

## 6. Initial data model

Use UUID primary keys, UTC timestamps, explicit lifecycle statuses, and
Flyway migrations. Prefer normalized relational data for core entities.
Use JSONB only for variable provider metadata or flexible model outputs,
not as a substitute for relational modeling.

Suggested tables (fields are indicative; refine during implementation):

-   `users`: `id`, display fields, created/updated timestamps.
-   `external_identities`: `id`, `user_id`, provider, provider subject
    (`sub`), email snapshot, timestamps; unique provider + subject.
-   `workspaces`: `id`, name, timezone, brand profile
    reference/settings, timestamps.
-   `workspace_members`: `workspace_id`, `user_id`, role, created
    timestamp; unique workspace + user.
-   `clients`: `id`, `workspace_id`, name, email, phone, notes,
    `archived_at`, `version`, timestamps. (Implemented in Milestone 3.)
-   `photo_sessions`: `id`, `workspace_id`, optional `client_id`
    (composite foreign key with `workspace_id`), title, session type,
    scheduled start/end (UTC), location, notes, status (`INQUIRY`,
    `BOOKED`, `COMPLETED`, `DELIVERED`, `CANCELLED`), `archived_at`,
    `version`, timestamps. (Implemented in Milestone 3; see D9 and D10
    in `docs/decisions.md`.)
-   `photo_assets`: `id`, `workspace_id`, optional `session_id`
    (composite foreign key with `workspace_id`), S3 object keys
    (original, thumbnail, preview), original filename, content type,
    byte size, SHA-256 checksum, upright dimensions, processing status
    (`PENDING_UPLOAD`, `PROCESSING`, `READY`, `REJECTED`, `FAILED`),
    safe failure reason, timestamps. (Implemented in Milestone 4; see
    D11.)
-   `galleries`: `id`, `workspace_id`, `session_id` (composite foreign
    key), title, status (`DRAFT`, `PUBLISHED`), download setting
    (`NONE`, `PREVIEWS`, `ORIGINALS`), archived timestamp, version,
    timestamps. (Implemented in Milestone 5; see D14.)
-   `gallery_assets`: `gallery_id`, `asset_id`, position. Composite
    foreign keys on `(workspace_id, session_id, …)` make every photo
    belong to the gallery's own session and workspace. The download
    setting is per gallery, not per photo.
-   `gallery_share_links`: SHA-256 token hash, gallery ID, required
    expiry, revoked timestamp and user, creating user, created
    timestamp. The raw token is never stored.
-   `gallery_access_events`: link ID, kind (`VIEW`, `DOWNLOAD`),
    downloaded asset ID, timestamp. No visitor data (IP address, user
    agent).
-   `tasks`: `id`, `workspace_id`, optional client/session links
    (composite foreign keys), title, description, due date (a calendar
    date in the workspace time zone), status (`OPEN`, `DONE`), optional
    assignee (composite foreign key to `workspace_members`), source
    (`MANUAL`, `AUTOMATION`), the creating automation run (unique),
    completion time and user, `version`, timestamps. (Implemented in
    Milestone 7; see D17.)
-   `brand_profiles`: one per workspace: voice, audience, preferences,
    words to avoid, version. (Implemented in Milestone 6; see D16.)
-   `content_batches`: `id`, `workspace_id`, `session_id` (composite
    foreign key), status (`PENDING`, `SUCCEEDED`, `FAILED`), the input
    (session type snapshot, notes, caption count, hashtag and call-to-
    action options), run count, safe failure reason, provider, model,
    prompt version, token counts, carousel rationale, story ideas,
    warnings, creating user, timestamps. `content_batch_assets` holds
    its photos in selection order and the suggested carousel position,
    with composite foreign keys to the session's photos.
-   `content_drafts`: `id`, `workspace_id`, batch ID, position, status
    (`DRAFT`, `APPROVED`), current version, approved version, approver,
    approval time. `content_draft_versions` holds every version's
    caption, call to action, and hashtags, with its source (`GENERATED`
    or `EDITED`) and editor. (Implemented in Milestone 6; see D16.)
-   `integration_connections`: `id`, `workspace_id`, provider, encrypted
    token reference, granted scopes, connection status, sync cursor,
    timestamps.
-   `email_threads` / `email_messages`: only add when Gmail integration
    is implemented; minimize retained content and store provider IDs.
-   `calendar_events`: add when internal calendar behavior is needed.
-   `automation_rules`: only for defined, supported rules initially; do
    not build an arbitrary workflow language prematurely. Milestone 7
    has one kind: trigger session status, task title, days until due,
    enabled flag, `version`. (See D17.)
-   `automation_runs`: rule, session, and client; the task title and due
    date fixed when the rule fired; status (`PENDING`, `SUCCEEDED`,
    `FAILED`), run count, safe failure reason, triggering user, start
    and end timestamps. Unique per rule and session. Attempts and the
    last error live on the run's `background_jobs` rows. Approval
    references come with rules that need approval. (Implemented in
    Milestone 7; see D17.)
-   `background_jobs` or equivalent job records: job type, entity
    reference, status, idempotency key, attempts, timestamps, safe error
    summary. (Implemented in Milestone 4 as `background_jobs`, with the
    next attempt time, lease, dispatch time, and correlation ID; see
    D12.)
-   `audit_events`: actor, workspace, action, target, timestamp,
    correlation ID, safe metadata. (Implemented in Milestone 7 with a
    short code-built `detail` text as the safe metadata; see D17.)

Use foreign keys and constraints. Define deletion/retention behavior for
related records and files. Avoid logging full email bodies, access
tokens, share tokens, or private photo URLs.

## 7. Authentication and sessions

-   Use Google OpenID Connect for sign-in.
-   Validate ID token signature, issuer, audience, expiry, and
    nonce/state protections using a maintained library.
-   Use Google's stable `sub` as the external identity key; do not use
    email as the immutable identity key.
-   Establish a server-side application session after authentication.
-   Use `Secure`, `HttpOnly`, appropriately configured `SameSite`
    cookies and CSRF protection for state-changing requests.
-   Keep login scopes separate from Gmail/Calendar API scopes.
-   Provide sign-out and account/workspace access checks.
-   Store OAuth refresh tokens only on the server, encrypted at rest,
    with narrowly scoped access; support disconnect/revoke and deletion.

Do not implement custom password authentication unless a concrete
product requirement emerges.

## 8. Photo uploads and private galleries

### Upload flow

1.  Authenticated user requests an upload for an asset in a specific
    workspace/session.
2.  API authorizes workspace membership and validates metadata/limits.
3.  API creates a pending asset record and returns a short-lived S3
    presigned upload URL for a server-generated object key.
4.  Browser uploads directly to private S3.
5.  Browser confirms upload, or an S3 event triggers
    validation/processing. (Milestone 4 uses browser confirmation only;
    see D11.)
6.  Worker verifies object metadata and actual file signature, then
    creates previews/thumbnails and updates asset status.
7.  The asset becomes available only after validation and processing
    succeed.

Apply file-size and content-type allowlists, signature validation,
quotas, rate limits, and malware scanning where appropriate. Do not
trust filenames or MIME types supplied by the browser. Use separate
object-key prefixes or buckets for originals and derivatives where
helpful. Ensure orphaned uploads are cleaned up.

### Gallery access

-   S3 buckets and objects remain private.
-   Authenticated workspace members access galleries through normal
    authorization.
-   External clients access a specific gallery through an unguessable,
    expiring share token.
-   Store only a hash of the share token; support expiry and revocation.
-   Resolve the token on the server and issue short-lived, narrowly
    scoped asset URLs as needed.
-   Apply gallery-level view/download settings and record relevant
    access/download events.
-   Never expose permanent public object URLs.
-   Plan for deletion, retention, export, and link revocation.

Milestone 5 implements this (D14). Visitors open `#/g/<token>` in the
SPA; the token stays in the URL fragment and is sent to the API only in
the `X-Gallery-Token` header of `/api/public/gallery` requests.

### Retention and deletion of gallery data

-   **Galleries never own photos.** Removing a photo from a gallery
    deletes only the association. Archiving a gallery closes it to
    visitors and keeps everything. The original, preview, and
    thumbnail stay in storage as long as the photo asset exists.
-   **Photo deletion is not built yet** (out of Milestone 5). When it
    is, deleting an asset removes it from every gallery (`ON DELETE
    CASCADE` on `gallery_assets`), and the object deletes follow the
    abandoned-upload cleanup's order: object first, then row. With
    bucket versioning, deleted objects stay recoverable as noncurrent
    versions for 30 days, then expire.
-   **Share links are revoked, never deleted.** An expired or revoked
    link keeps its row and access history. Neither contains the token.
-   **Access events** keep only link, kind, photo, and time. They are
    kept for the gallery's lifetime. Deleting a workspace deletes its
    galleries, links, and events by cascade.
-   **Unpublishing, archiving, expiry, and revocation take effect on
    the next request.** Presigned URLs already issued (at most 10
    minutes for previews, 5 minutes for downloads) keep working until
    they expire. This is the accepted window for "revoked".

## 9. Background jobs and event handling

Use SQS for work that can outlive an HTTP request or needs retries. A
worker should claim/execute a job, update status, and emit structured
logs with a correlation ID.

Required behavior: - Idempotent processing using a stable job or event
key. - Bounded retries with exponential backoff and jitter. -
Dead-letter queue and a documented re-drive procedure. - Timeouts and
limits for external calls. - Persisted status so the UI can show
pending/running/succeeded/failed. - Safe error summaries; detailed
secrets or private payloads must not enter logs. - Metrics and alarms
for queue depth, repeated failures, and stuck jobs.

When a database transaction needs to cause a background job, avoid the
dual-write failure where the DB commits but message publication fails.
Use a transactional outbox or a durable job table with a reliable
dispatcher. Do not add an event bus or complex orchestration service
until a real need exists.

## 10. AI integration

Create a provider-neutral application interface, for example
`ContentGenerationProvider`, with typed request/response objects. The
domain layer should not depend on a particular model SDK.

Inputs may include: - selected asset IDs and generated image
descriptions, - session type and photographer notes, - brand voice and
audience, - desired output formats and number of alternatives, -
constraints such as words/phrases to avoid.

Outputs may include: - concise photo descriptions or candidate photo
selections, - caption alternatives, - calls to action and optional
hashtag suggestions, - carousel ordering and rationale, - Story/Reel
concepts, - missing-information flags and confidence warnings.

Requirements: - Validate structured output and handle
malformed/incomplete responses. - Persist prompt/template version and
provider/model metadata, but never secrets. - Keep generated drafts
editable and versioned. - Do not invent client stories, promises, dates,
or details absent from source data. - Treat uploaded content and email
content as untrusted data, not instructions to the agent. - Do not allow
prompt content to override application authorization or safety
policies. - AI can propose actions; deterministic application code
authorizes and executes them. - Do not send photos or personal data to a
provider without an explicit product/privacy decision and appropriate
disclosure.

Milestone 6 implements this as `ContentGenerationProvider` in the
`content` module, with a mock provider (default) and an Anthropic
adapter. D16 records what is sent to the provider (selected photo
previews without metadata, the notes the photographer types, the
session type, and the brand profile; never client details), the
validation rules, retries, and versioned, human-approved drafts.

## 11. Gmail and Google Calendar integrations

### Gmail

Implement after the first photo-to-content vertical slice.

-   Login and mailbox connection are separate flows.
-   Ask for consent only when the user enables mailbox features.
-   Start with the minimum scopes required for a clearly defined
    feature.
-   Prefer drafts and user review before sending.
-   Do not request delete or full-mailbox modification permissions
    unless a feature demonstrably requires them.
-   Use provider message/thread IDs, sync cursors/history where
    supported, and idempotent sync.
-   Minimize stored message bodies and define retention/deletion
    behavior.
-   Never log OAuth tokens or full private email content.
-   Maintain an audit record for draft creation, approval, and sending.
-   Verify current Google OAuth verification and restricted-scope
    security-assessment requirements before public launch.

### Google Calendar

-   Add after core session/task flows are stable.
-   Define a source-of-truth policy before bidirectional sync.
-   Handle duplicate, deleted, changed, and out-of-order events
    idempotently.
-   Make timezone behavior explicit and test daylight-saving
    transitions.
-   Do not silently overwrite external changes.

## 12. API conventions and validation

-   Use REST endpoints with explicit request/response DTOs.
-   Validate all inputs server-side.
-   Use consistent error responses with a correlation ID; do not expose
    stack traces or secrets.
-   Use pagination for lists and stable sorting.
-   Use optimistic locking/version fields for concurrently edited
    records where needed.
-   Use UTC timestamps in storage and convert for display using
    workspace timezone.
-   Keep provider-specific models out of public API responses.
-   Generate and maintain an OpenAPI specification if it improves
    frontend integration and testability.

## 13. Security and privacy baseline

-   TLS in transit and encryption at rest.
-   Least-privilege IAM roles and per-environment credentials.
-   No secrets committed to the repository or exposed to frontend
    bundles.
-   Secure cookies, CSRF protection, input validation, output encoding,
    and rate limits.
-   Tenant isolation tests for every workspace-owned resource family.
-   Private object storage and short-lived presigned URLs.
-   Audit sensitive operations and approvals.
-   Backups and a tested restore procedure.
-   Defined data retention, account deletion, workspace export, and
    integration disconnect flows.
-   Limit personally identifiable information in logs and telemetry.
-   Establish client photo consent and rights/retention policies before
    commercial launch.

## 14. Observability and operations

-   Structured JSON logs with request/correlation IDs.
-   Metrics for API latency/errors, job queue depth, job failure rate,
    upload/processing failures, AI usage/cost, and integration sync
    health.
-   Alarms for sustained errors, DLQ messages, stalled jobs, and unusual
    resource usage.
-   Health/readiness endpoints.
-   Separate local, test, staging, and production configuration.
-   Document deployment, rollback, secret rotation, backup restore, and
    failed-job recovery.

## 15. Key architecture decisions to preserve

1.  Modular monolith first; no premature microservices.
2.  PostgreSQL for business records; S3 for binaries.
3.  Workspace-scoped authorization on the server.
4.  Private storage and expiring/revocable gallery links.
5.  SQS-backed jobs with idempotency, retries, and durable status.
6.  Provider adapters for identity, storage, AI, and external
    integrations.
7.  AI proposes; application policy and user approval control
    consequential actions.
8.  Google login is independent from Gmail/Calendar access.
9.  Delay Gmail, Calendar sync, Instagram publishing, billing, and
    generic workflow builders until core value is proven.
