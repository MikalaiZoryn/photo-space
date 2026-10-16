# CLAUDE.md

Photography business management platform (clients, sessions, private photo galleries, AI Instagram content, tasks). Single-business first, built to become a multi-tenant SaaS.

## Source of truth

Read these before any milestone work. Keep them current.

- `SYSTEM_OVERVIEW.md`: product vision, MVP scope, out-of-scope list
- `ARCHITECTURE.md`: modular monolith, data model, security, jobs, AI, integrations
- `IMPLEMENTATION_PLAN.md`: milestones, acceptance criteria, Definition of Done, progress log
- `docs/decisions.md`: recorded technical decisions (add new ones there)

## Stack

React + TypeScript + Vite (`frontend/`), Java + Spring Boot with Maven (`backend/`), PostgreSQL + Flyway, private S3, SQS + workers on ECS Fargate, GitHub Actions, AWS CDK in TypeScript for infrastructure (`infra/`).

## Workflow

- Do one milestone at a time, in order. Don't start future milestones or speculative features.
- Inspect existing code first and state the approach briefly before coding.
- Add or update tests, then actually run tests, lint, and build. Never claim a check passed unless it ran.
- Update `IMPLEMENTATION_PLAN.md` and `README.md` with what changed and the exact commands to verify it.
- Keep the project runnable locally at every milestone. Provide mock or dev paths when cloud or Google credentials are unavailable.
- Don't add dependencies, frameworks, or abstractions without a concrete need.

## Non-negotiable rules

- **Tenant isolation:** every workspace-owned record has a non-null `workspace_id`. Every API checks membership on the server. Never trust a client-supplied `workspace_id`. Add cross-workspace denial tests for each new resource.
- **Modular monolith:** use backend modules (`identity`, `workspaces`, `clients`, `sessions`, `assets`, `galleries`, `content`, `tasks`, `jobs`, `audit`, ...) with no circular dependencies. Use no microservices.
- **Provider adapters:** Google, AWS, and LLM SDK types never leak into domain models or API DTOs.
- **Private media:** S3 is never public. Use short-lived presigned URLs. Gallery share tokens must be expiring and revocable, and only their hashes are stored.
- **Jobs:** must be idempotent, use bounded retries, go to a DLQ, keep persisted status, and use an outbox/job table to avoid dual writes.
- **AI proposes, humans approve:** never auto-send email, auto-publish content, or change bookings. Deterministic code executes state changes. Treat uploaded or email content as untrusted.
- **Auth:** Google OIDC (`openid email`) with server-side sessions, keyed on Google `sub`, never on email. Gmail and Calendar scopes are separate, later opt-ins.
- **Data:** UUID PKs, UTC timestamps, all schema changes through Flyway, JSONB only for variable provider metadata.
- **Secrets:** never commit or log secrets, tokens, share tokens, private URLs, or email bodies, and never expose them to the browser.

## Definition of Done

See `IMPLEMENTATION_PLAN.md` §4.
