# photo-space

A management platform for photography businesses. It covers clients and sessions, private photo galleries with client downloads, AI-assisted Instagram content drafts, and tasks. It is built first for a single photography business and designed to become a multi-tenant SaaS later.

## Project documents

| Document | Purpose |
| --- | --- |
| [SYSTEM_OVERVIEW.md](SYSTEM_OVERVIEW.md) | Product vision, MVP scope, what is out of scope |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architecture, data model, security, jobs, AI, integrations |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | Milestones, acceptance criteria, Definition of Done, progress |
| [docs/decisions.md](docs/decisions.md) | Recorded technical decisions |
| [CLAUDE.md](CLAUDE.md) | Working rules for Claude Code |

## Repository layout

```text
frontend/          React + TypeScript + Vite web app (Tailwind CSS, shadcn/ui, Vitest, oxlint)
backend/           Java + Spring Boot modular monolith (Maven wrapper, Flyway)
infra/             AWS CDK app in TypeScript: private photo bucket and job queues per environment
local/             Config for the local emulators (ElasticMQ queues)
docs/              Decisions and operational docs
compose.yaml       Local PostgreSQL, S3 emulator, and SQS emulator for development
.github/workflows/ CI: backend, frontend, and infrastructure checks on every pull request
```

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| Java | 25 (LTS) | Temurin or similar. Maven itself comes from the committed wrapper (`./mvnw`). |
| Node.js | 24 (LTS), npm 11+ | `frontend/.nvmrc` and `infra/.nvmrc` pin the major version. |
| Docker | Engine 29+ with Compose v2+ | Runs local PostgreSQL 18, the S3 and SQS emulators, and the Testcontainers integration tests. |

Local development and all tests need no cloud credentials. The CDK CLI is a dev dependency of `infra/`; deploying (Milestone 8) will also need the AWS CLI v2 and an AWS account.

## Development commands

Run these from the repository root unless a directory is shown.

### Start the local stack

```bash
docker compose up -d --wait               # PostgreSQL 18 on :5432, S3 emulator on :9090, SQS emulator on :9324
(cd backend && ./mvnw spring-boot:run)    # API and job worker on http://localhost:8080; Flyway migrates on startup
(cd frontend && npm install && npm run dev)  # UI on http://localhost:5173; /api is proxied to :8080
```

Open http://localhost:5173. Without Google credentials the page shows "No sign-in method is configured". Use one of the two sign-in options below. The system status line at the bottom should show "Backend UP, database UP".

- Stop the services with `docker compose down`. Add `-v` to also wipe the data volumes (database and stored photos), which gives you a clean start. The SQS emulator keeps queues in memory; after a restart the backend re-sends unfinished jobs from the database within 15 minutes.
- If a port is taken, set `POSTGRES_PORT` and `DB_URL`, `S3_PORT` and `STORAGE_ENDPOINT`, or `SQS_PORT`, `JOBS_SQS_ENDPOINT`, and `JOBS_QUEUE_URL`. `.env.example` lists the variables.
- To run without Compose, `(cd backend && ./mvnw spring-boot:test-run)` starts the backend against throwaway Testcontainers PostgreSQL, S3, and SQS emulators, with dev sign-in enabled. Photos uploaded this way are lost when it stops.

### Signing in locally

Pick one of these options. Both use the same server-side session, CSRF, and workspace checks.

**Option A: dev sign-in (no Google credentials needed).** Run the backend from the test classpath instead of `spring-boot:run`:

```bash
(cd backend && ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=compose-db)  # compose.yaml services
(cd backend && ./mvnw spring-boot:test-run)                                        # throwaway Testcontainers services
```

The sign-in page then shows a "Development sign-in" form. Any email works, with no password. Each email is a separate user, so two emails let you try cross-workspace access. This code lives only in `backend/src/test` and is never packaged into the application jar (D8 in `docs/decisions.md`).

**Option B: real Google sign-in.**

1. In Google Cloud Console, create an OAuth client ID of type "Web application". Add the authorized redirect URI `http://localhost:5173/api/auth/oauth2/callback/google`.
2. Copy `.env.example` to `.env` (it is git-ignored) and set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.
3. Start the backend with those variables: `set -a; source .env; set +a; (cd backend && ./mvnw spring-boot:run)`.
4. Use the app through http://localhost:5173 so the redirect URI matches.

Sign-in asks only for `openid email`. Gmail and Calendar access will be separate opt-in connections later.

The session cookie is `Secure`. Chrome and Firefox accept it on `http://localhost`. For Safari, set `SESSION_COOKIE_SECURE=false` locally.

### API

| Endpoint | Access |
| --- | --- |
| `GET /api/auth/options` | Public. Lists the available sign-in methods. |
| `GET /api/auth/csrf` | Public. Sets the `XSRF-TOKEN` cookie. |
| `GET /api/auth/oauth2/authorization/google` | Public. Starts Google sign-in (browser navigation). |
| `POST /api/auth/logout` | Signed in, with CSRF. Ends the session (204). |
| `GET /api/me` | Signed in. Returns the current user. |
| `GET /api/workspaces`, `POST /api/workspaces` | Signed in. Lists your workspaces, or creates one with you as owner. |
| `GET /api/workspaces/{id}`, `GET /api/workspaces/{id}/members` | Workspace members. Everyone else gets 404. |
| `PUT /api/workspaces/{id}` | Workspace owners (members get 403, non-members get 404). |
| `GET /api/workspaces/{id}/clients?q=&archived=false&page=0&size=25` | Members. Clients sorted by name; `q` searches name and email. |
| `POST /api/workspaces/{id}/clients` | Members. Creates a client: `{name, email, phone, notes}`. |
| `GET`, `PUT /api/workspaces/{id}/clients/{clientId}` | Members. `PUT` takes `{version, client: {…}}`. |
| `POST /api/workspaces/{id}/clients/{clientId}/archive`, `…/restore` | Members. Idempotent. |
| `GET /api/workspaces/{id}/sessions?status=&clientId=&archived=false&page=0&size=25` | Members. Newest first, unscheduled on top. |
| `POST /api/workspaces/{id}/sessions` | Members. Creates an inquiry: `{title, sessionType, clientId, startLocal, endLocal, location, notes}`. |
| `GET`, `PUT /api/workspaces/{id}/sessions/{sessionId}` | Members. `PUT` takes `{version, session: {…}}`. |
| `POST /api/workspaces/{id}/sessions/{sessionId}/status` | Members. `{status}`; only allowed transitions (409 otherwise). |
| `POST /api/workspaces/{id}/sessions/{sessionId}/archive`, `…/restore` | Members. Idempotent. |
| `GET /api/workspaces/{id}/sessions/{sessionId}/assets?page=0&size=25` | Members. The session's photos in upload order, in every status. |
| `POST /api/workspaces/{id}/sessions/{sessionId}/assets` | Members. Starts an upload: `{filename, contentType, byteSize}`. Returns `{asset, upload: {url, method, headers, expiresAt}}`. |
| `GET /api/workspaces/{id}/assets/{assetId}` | Members. One photo with its status, and thumbnail and preview URLs once `READY`. |
| `POST /api/workspaces/{id}/assets/{assetId}/complete` | Members. Reports that the upload finished, which starts processing. Idempotent; 409 if the file is not in storage yet. |
| `GET /api/workspaces/{id}/galleries?sessionId=&archived=false&page=0&size=25` | Members. Newest first, with `photoCount`. |
| `POST /api/workspaces/{id}/galleries` | Members. Creates a draft: `{sessionId, gallery: {title, downloads}}`. |
| `GET`, `PUT /api/workspaces/{id}/galleries/{galleryId}` | Members. Returns `{gallery, photos}`. `PUT` takes `{version, gallery: {title, downloads}}`. |
| `PUT /api/workspaces/{id}/galleries/{galleryId}/photos` | Members. `{version, assetIds}`: the whole ordered list of ready photos of the gallery's session. |
| `POST /api/workspaces/{id}/galleries/{galleryId}/status` | Members. `{status: "DRAFT" \| "PUBLISHED"}`. |
| `POST /api/workspaces/{id}/galleries/{galleryId}/archive`, `…/restore` | Members. Idempotent. |
| `GET`, `POST /api/workspaces/{id}/galleries/{galleryId}/links` | Members. `POST {validDays}` (1–365) returns `{link, token}`; the token is shown only this once. |
| `POST /api/workspaces/{id}/galleries/{galleryId}/links/{linkId}/revoke` | Members. Idempotent. |
| `GET /api/workspaces/{id}/content/settings` | Members. The AI provider (`mock` or `anthropic`), model, and limits. |
| `GET`, `PUT /api/workspaces/{id}/content/brand-profile` | Members. `PUT` takes `{version, profile: {voice, audience, preferences, avoid}}`; version 0 before the first save. |
| `GET /api/workspaces/{id}/content/batches?sessionId=&page=0&size=25` | Members. Generation batches, newest first, with photo, draft, and approval counts. |
| `POST /api/workspaces/{id}/content/batches` | Members. `{sessionId, assetIds, notes, captionCount, includeHashtags, includeCallToAction}`. Returns the `PENDING` batch; a job writes the drafts. |
| `GET /api/workspaces/{id}/content/batches/{batchId}` | Members. `{batch, photos, carousel, drafts}`. Poll it while `PENDING`. |
| `POST /api/workspaces/{id}/content/batches/{batchId}/retry`, `…/regenerate` | Members. Retry runs a `FAILED` batch again; regenerate creates a new batch from the same input (201). |
| `GET`, `PUT /api/workspaces/{id}/content/drafts/{draftId}` | Members. `GET` returns `{draft, versions}`. `PUT {version, draft: {caption, callToAction, hashtags}}` saves a new version. |
| `POST /api/workspaces/{id}/content/drafts/{draftId}/status` | Members. `{version, status: "APPROVED" \| "DRAFT"}`; approves exactly that version. |
| `GET /api/workspaces/{id}/tasks?status=&clientId=&sessionId=&assigneeId=&overdue=false&page=0&size=25` | Members. Open tasks first, then by due date (undated last). `overdue=true` keeps open tasks due before today in the workspace time zone. |
| `POST /api/workspaces/{id}/tasks` | Members. `{title, description, dueDate, clientId, sessionId, assigneeId}`. New tasks are `OPEN`. |
| `GET`, `PUT`, `DELETE /api/workspaces/{id}/tasks/{taskId}` | Members. `PUT` takes `{version, task: {…}}`; `DELETE` returns 204. |
| `POST /api/workspaces/{id}/tasks/{taskId}/status` | Members. `{status: "DONE" \| "OPEN"}`. Idempotent. |
| `GET /api/workspaces/{id}/automation/rules` | Members. The workspace's rules, oldest first. |
| `POST /api/workspaces/{id}/automation/rules`, `PUT …/rules/{ruleId}` | Owners (members get 403). `{triggerStatus, taskTitle, dueInDays, enabled}`; `PUT` takes `{version, rule: {…}}`. |
| `GET /api/workspaces/{id}/automation/runs?status=&page=0&size=25` | Members. Rule runs, newest first, with the created `taskId`, or the attempt count and last problem while pending. |
| `POST /api/workspaces/{id}/automation/runs/{runId}/retry` | Members. Starts a `FAILED` run again (409 otherwise). |
| `GET /api/workspaces/{id}/jobs?status=&page=0&size=25` | Owners. Background jobs with status, attempts, and a safe error summary. Read-only. |
| `GET /api/workspaces/{id}/audit-events?targetId=&page=0&size=25` | Owners. The audit log, newest first. |
| `GET /api/public/gallery` | Gallery visitors, with the `X-Gallery-Token` header. The gallery the link opens. |
| `POST /api/public/gallery/photos/{assetId}/download` | Gallery visitors, with the token header and CSRF. Returns `{url, expiresAt}` when the gallery allows downloads. |

Unauthenticated calls get a 401 problem body. State-changing calls need the `X-XSRF-TOKEN` header, which must match the `XSRF-TOKEN` cookie. Every response carries an `X-Correlation-Id` header, and error bodies include the same `correlationId`. All workspace-owned APIs must live under `/api/workspaces/{workspaceId}/`, where one central check enforces membership (see D8).

Conventions for workspace resources (D9 and D10 in `docs/decisions.md`):

- Lists return `{items, page, size, totalItems}`. `page` starts at 0, and `size` is 1–100 (default 25).
- Records are archived, not deleted. Archived records drop out of lists unless you pass `archived=true`, and must be restored before they can be edited.
- `PUT` sends back the `version` you loaded. A stale version gets a 409, with an explanation in `detail`.
- Session `startLocal` and `endLocal` are wall-clock times in the workspace time zone (`2026-11-07T10:30`, no offset). The server stores UTC, rejects times skipped by a daylight-saving change, and returns both `startsAt` (UTC) and `startLocal`.
- The session lifecycle is `INQUIRY → BOOKED → COMPLETED → DELIVERED`. `INQUIRY` and `BOOKED` can be cancelled, and `CANCELLED` can reopen as `INQUIRY`. Booking needs a start time. Each session response lists its `allowedStatuses`.

Photo uploads (D11 and D12):

- The browser `PUT`s the file straight to private storage at `upload.url`, sending exactly `upload.headers`. The URL works for 15 minutes, for that one object, type, and size. Then it calls `…/complete`.
- Accepted: JPEG and PNG, up to 100 MB and 200 megapixels. Each workspace may have up to 100 unfinished uploads and 100 GB of photos. Breaking a limit gets a 400 field error (type, size, name) or a 409 explanation (limits, archived session).
- Asset status: `PENDING_UPLOAD → PROCESSING → READY`, or `REJECTED` (not a valid photo; `failureReason` says why and the original is deleted) or `FAILED` (processing kept failing after 5 attempts).
- `thumbnailUrl` (480 px) and `previewUrl` (2048 px) are presigned and expire after 10 minutes; fetch the asset again for fresh ones. The original never has a URL here.
- Uploads not completed within 24 hours are deleted.

Galleries and share links (D14):

- A gallery holds ready photos of one session, in order. `downloads` is `NONE` (view only), `PREVIEWS` (2048 px JPEG), or `ORIGINALS`. Links open a gallery only while it is `PUBLISHED` and not archived.
- A link's token is returned once and only its SHA-256 is stored. A lost link is replaced, not recovered. Links expire after 1–365 days and can be revoked.
- Clients open `/#/g/<token>`. The token stays in the URL fragment and reaches the API only in the `X-Gallery-Token` header. Every link that doesn't work gets the same 404.
- Visitor image URLs expire after 10 minutes and download URLs after 5. Views and downloads are counted per link, with no visitor data.
- Removing a photo from a gallery, or archiving a gallery, never deletes the photo. See ARCHITECTURE §8 for retention and deletion.

AI content drafts (D16):

- `CONTENT_PROVIDER` is `mock` by default: sample captions written on the server, with no network calls and no account. Set `CONTENT_PROVIDER=anthropic` and `ANTHROPIC_API_KEY` to use Claude (`ANTHROPIC_MODEL`, default `claude-opus-5-5`). The backend refuses to start with `anthropic` and no key.
- A batch takes 1–10 ready photos of one session and asks for 1–5 caption options. Only the selected photos' 2048 px previews (no EXIF), the notes in the request, the session type, and the brand profile are sent. Client names and contact details never are.
- Batch status: `PENDING` (queued, generating, or waiting for an automatic retry, with `failureReason` saying why) → `SUCCEEDED` or `FAILED`. Rate limits, timeouts, and unusable answers are retried up to 5 times with backoff; refusals, rejected requests, and setup problems fail at once. `…/retry` starts a failed batch again.
- Drafts are proposals. Each edit is a new version; approval names the version, and editing makes the draft a draft again. Each draft lists `checks`: handles, links, emails, phone numbers, and prices that are not in your notes or brand profile. Nothing is ever posted: copy an approved draft into Instagram yourself.

Tasks, automation, and the audit log (D17):

- `dueDate` is a calendar date in the workspace time zone (`2026-11-07`). Tasks link to an optional client, session, and assignee of the same workspace. As with sessions, a task can keep a link to an archived client or session but cannot be newly linked to one. Removing a member unassigns their tasks. Tasks are deleted, not archived.
- One rule kind exists: when a session changes to `triggerStatus` (`BOOKED`, `COMPLETED`, `DELIVERED`, or `CANCELLED`), create a task titled `taskTitle`, due `dueInDays` (0–365) days after the change, linked to the session and its client. Rules are disabled, not deleted.
- A rule fires at most once per session, however often the session reaches that status. The run is recorded in the transaction of the status change and carried out by a background job. The job creates the task once, even if it runs twice. A run that keeps failing becomes `FAILED` after 5 attempts and can be retried.
- Audited: session status changes, task changes, rule changes, run outcomes and retries, gallery publishing, share links, and draft approvals. Each event has the actor (none for the system), the target, a short code-built detail (never user text, tokens, or URLs), and the correlation ID.

### Clients and sessions: a quick manual check

With the stack running and dev sign-in (Option A):

1. Sign in, create a workspace in `Europe/Warsaw`, and open the **Clients** tab. Add a client.
2. On the client's page, choose **New session for …**. Enter a start such as 2026-11-07 10:30, then save. The session page shows 10:30, whatever your browser's time zone is.
3. Try **Mark booked**, then **Mark completed**. Only allowed transitions are offered. Archive the session and check that it leaves the list unless **Show archived** is ticked.
4. Sign in as a second email in a private window. That user's workspace shows none of the first user's data. Pasting a session URL from the first user shows "not found".

### Photo uploads: a quick manual check

With the stack running and dev sign-in:

1. Open a session and choose **Upload photos**. Pick a few JPEG or PNG files, including a portrait phone photo.
2. Each file shows "uploading…", then the grid shows "Processing…" and, a few seconds later, the thumbnail. The portrait photo is shown upright. Clicking a thumbnail opens the 2048 px preview.
3. Rename a text file to `.jpg` and upload it. It ends up "Rejected" with "This file is not a JPEG or PNG image."
4. A GIF is refused in the browser before anything is sent.
5. In another user's window, the session URL shows "not found", and the API returns 404 for the photo list.

To see the jobs behind this: `docker compose exec postgres psql -U photospace -c "SELECT job_type, status, attempts, last_error FROM background_jobs ORDER BY created_at DESC LIMIT 5"`.

### Galleries: a quick manual check

With the stack running, dev sign-in, and a session with a few ready photos:

1. On the session page, under **Galleries**, choose **New gallery**. Set a title and **Full-resolution originals**, then **Create gallery**.
2. On the gallery page, **Add all**, then reorder with the arrows and remove one. Choose **Publish**.
3. Under **Share links**, pick 7 days and **Create link**. Copy the URL; it is not shown again.
4. Open the URL in a private window. The gallery shows without sign-in, in your order, and **Download original** saves the file under its uploaded name.
5. Back in the first window, **Unpublish**. Reloading the private window shows "This gallery link does not work". **Publish** again, and it works again. **Revoke** the link, and it stops for good. The link list shows its views and downloads.
6. In another user's window, the gallery URL shows "not found".

### AI content: a quick manual check

With the stack running, dev sign-in, and a session with a few ready photos (the default mock provider needs no account):

1. Open **Content**, fill in the brand profile (for example, voice "warm and calm"), and **Save profile**.
2. On the session page, under **Instagram drafts**, choose **Create drafts**. Pick two photos (the numbers show the order), edit the notes, choose 3 caption options, and **Write drafts**.
3. The batch page shows "Writing drafts…" and, a few seconds later, three drafts, the suggested carousel order, and Story ideas.
4. Edit a draft and add "@someone" to it, then **Save changes**: it shows "Version 2 · edited" and a review hint about "@someone". **Approve** it; **Copy for Instagram** appears. Edit it again, and it needs a new approval. **History** lists every version.
5. Reload the page: the drafts and approvals are still there. **Regenerate** creates a new batch from the same photos and notes.
6. To see a failure, restart the backend with `CONTENT_PROVIDER=anthropic ANTHROPIC_API_KEY=invalid`. A new batch fails with "AI generation is not set up correctly…". Restart without them, and **Retry** produces drafts.
7. In another user's window, the batch URL shows "not found".

### Tasks and automation: a quick manual check

With the stack running and dev sign-in:

1. Open **Automation** and **Add rule**: when a session is marked **Booked**, create "Send the contract", due after 2 days.
2. Create a session with a start time and **Mark booked**. A few seconds later, the session's **Tasks** panel shows "Send the contract", due two days from today, marked **Automated**. **Automation → Recent runs** shows "Task created".
3. Cancel the session, reopen it as an inquiry, and book it again. No second task appears: the rule fires once per session.
4. Tick the task's checkbox to complete it. On **Tasks**, switch **Show** to **Done** to find it again. **New task** creates a manual task with a due date and assignee.
5. **Activity** lists the rule, the status changes, the task created by "Automation", the run, and the completion.
6. In another user's window, the task, automation, and activity URLs show "not found".

To see a failed run, block task inserts for a moment: `docker compose exec postgres psql -U photospace -c "ALTER TABLE tasks ADD CONSTRAINT block_tasks CHECK (false) NOT VALID"`. Book another session: after 5 attempts, spread over 4 to 8 minutes, the run shows **Failed**, and owners see the job under **Failed background jobs**. Remove the block with `... -c "ALTER TABLE tasks DROP CONSTRAINT block_tasks"` and choose **Retry**.

### Health endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Used by the app. Returns `{"status":"UP","database":"UP"}`, or HTTP 503 when the database is down. |
| `GET /actuator/health/liveness`, `/actuator/health/readiness` | Infrastructure probes. Details are hidden, and no other actuator endpoints are exposed. |

### Checks (the same ones CI runs)

```bash
cd backend && ./mvnw verify          # unit tests (Surefire) + integration tests with Testcontainers (Failsafe); needs Docker
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
cd infra && npm ci && npm run typecheck && npm test && npx cdk synth -q -c env=staging -c appOrigin=https://staging.example.com
```

- Backend unit tests only: `./mvnw test`.
- Integration tests are the `*IT.java` classes. They share one application context and one set of clean containers (PostgreSQL 18, S3Mock, ElasticMQ), and run every Flyway migration from scratch. They talk to the app over real HTTP with session and CSRF cookies (`support/ApiClient`), upload to presigned URLs, and wait for the real job worker.
- Frontend tests in watch mode: `npm run test:watch`.

### Database migrations

Migrations are in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`. They run automatically when the backend starts. Never edit a migration that has already been applied; add a new one instead.

### CI

`.github/workflows/ci.yml` runs on every pull request and on every push to `main`. It has three jobs: the backend job runs `./mvnw verify`, the frontend job runs lint, typecheck, test, and build, and the infra job runs typecheck, test, and `cdk synth`.

### Infrastructure

`infra/` defines one storage stack per environment: the private photo bucket and the job queue with its dead-letter queue. See `infra/README.md`. Nothing has been deployed yet; deployment arrives with Milestone 8.

## Status

See the Progress section in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#progress).
