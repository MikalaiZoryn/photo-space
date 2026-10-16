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

Open http://localhost:5173. The page should show "Backend UP, database UP".

- Stop the database with `docker compose down`. Add `-v` to also wipe the data volume, which gives you a clean database.
- If port 5432 is taken, set `POSTGRES_PORT` and `DB_URL`. `.env.example` lists the variables.
- To run without Compose, `(cd backend && ./mvnw spring-boot:test-run)` starts the backend against a throwaway Testcontainers PostgreSQL.

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
- Integration tests are the `*IT.java` classes. Each starts a clean PostgreSQL 18 container and runs every Flyway migration from scratch.
- Frontend tests in watch mode: `npm run test:watch`.

### Database migrations

Migrations are in `backend/src/main/resources/db/migration` and are named `V<n>__<description>.sql`. They run automatically when the backend starts. Never edit a migration that has already been applied; add a new one instead.

### CI

`.github/workflows/ci.yml` runs on every pull request and on every push to `main`. It has two jobs: the backend job runs `./mvnw verify`, and the frontend job runs lint, typecheck, test, and build.

## Status

See the Progress section in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#progress).
