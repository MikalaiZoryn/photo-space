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
frontend/          React + TypeScript + Vite web app (Vitest, oxlint)
backend/           Java + Spring Boot modular monolith (Maven wrapper, Flyway)
infra/             AWS CDK app in TypeScript                    (Milestone 4/8)
docs/              Decisions and operational docs
compose.yaml       Local PostgreSQL for development
.github/workflows/ CI: backend and frontend checks on every pull request
```

## Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| Java | 25 (LTS) | Temurin or similar. Maven itself comes from the committed wrapper (`./mvnw`). |
| Node.js | 24 (LTS), npm 11+ | `frontend/.nvmrc` pins the major version. |
| Docker | Engine 29+ with Compose v2+ | Runs local PostgreSQL 18 and the Testcontainers integration tests. |

Later, for infrastructure work only: AWS CLI v2 and the AWS CDK CLI. Local development and all tests need no cloud credentials.

## Development commands

Run these from the repository root unless a directory is shown.

### Start the local stack

```bash
docker compose up -d --wait               # PostgreSQL 18 on 127.0.0.1:5432
(cd backend && ./mvnw spring-boot:run)    # API on http://localhost:8080; Flyway migrates on startup
(cd frontend && npm install && npm run dev)  # UI on http://localhost:5173; /api is proxied to :8080
```

Open http://localhost:5173. Without Google credentials the page shows "No sign-in method is configured". Use one of the two sign-in options below. The system status line at the bottom should show "Backend UP, database UP".

- Stop the database with `docker compose down`. Add `-v` to also wipe the data volume, which gives you a clean database.
- If port 5432 is taken, set `POSTGRES_PORT` and `DB_URL`. `.env.example` lists the variables.
- To run without Compose, `(cd backend && ./mvnw spring-boot:test-run)` starts the backend against a throwaway Testcontainers PostgreSQL, with dev sign-in enabled.

### Signing in locally

Pick one of these options. Both use the same server-side session, CSRF, and workspace checks.

**Option A: dev sign-in (no Google credentials needed).** Run the backend from the test classpath instead of `spring-boot:run`:

```bash
(cd backend && ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=compose-db)  # compose.yaml database
(cd backend && ./mvnw spring-boot:test-run)                                        # throwaway Testcontainers database
```

The sign-in page then shows a "Development sign-in" form. Any email works, with no password. Each email is a separate user, so two emails let you try cross-workspace access. This code lives only in `backend/src/test` and is never packaged into the application jar (D8 in `docs/decisions.md`).

**Option B: real Google sign-in.**

1. In Google Cloud Console, create an OAuth client ID of type "Web application". Add the authorized redirect URI `http://localhost:5173/api/auth/oauth2/callback/google`.
2. Copy `.env.example` to `.env` (it is git-ignored) and set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`.
3. Start the backend with those variables: `set -a; source .env; set +a; (cd backend && ./mvnw spring-boot:run)`.
4. Use the app through http://localhost:5173 so the redirect URI matches.

Sign-in asks only for `openid email`. Gmail and Calendar access will be separate opt-in connections later.

The session cookie is `Secure`. Chrome and Firefox accept it on `http://localhost`. For Safari, set `SESSION_COOKIE_SECURE=false` locally.

### API (Milestone 2)

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

Unauthenticated calls get a 401 problem body. State-changing calls need the `X-XSRF-TOKEN` header, which must match the `XSRF-TOKEN` cookie. Every response carries an `X-Correlation-Id` header, and error bodies include the same `correlationId`. All workspace-owned APIs must live under `/api/workspaces/{workspaceId}/`, where one central check enforces membership (see D8).

### Health endpoints

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Used by the app. Returns `{"status":"UP","database":"UP"}`, or HTTP 503 when the database is down. |
| `GET /actuator/health/liveness`, `/actuator/health/readiness` | Infrastructure probes. Details are hidden, and no other actuator endpoints are exposed. |

### Checks (the same ones CI runs)

```bash
cd backend && ./mvnw verify          # unit tests (Surefire) + integration tests with Testcontainers (Failsafe); needs Docker
cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build
```

- Backend unit tests only: `./mvnw test`.
- Integration tests are the `*IT.java` classes. They share one application context and one clean PostgreSQL 18 container, and run every Flyway migration from scratch. They talk to the app over real HTTP with session and CSRF cookies (`support/ApiClient`).
- Frontend tests in watch mode: `npm run test:watch`.

### Database migrations

Migrations are in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`. They run automatically when the backend starts. Never edit a migration that has already been applied; add a new one instead.

### CI

`.github/workflows/ci.yml` runs on every pull request and on every push to `main`. It has two jobs: the backend job runs `./mvnw verify`, and the frontend job runs lint, typecheck, test, and build.

## Status

See the Progress section in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#progress).
