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
frontend/   React + TypeScript + Vite web app            (Milestone 1)
backend/    Java + Spring Boot modular monolith (Maven)  (Milestone 1)
infra/      AWS CDK app in TypeScript                    (Milestone 4/8)
docs/       Decisions and operational docs
```

## Prerequisites

These are needed from Milestone 1 onward. Milestone 1 will pin exact versions in the build files.

- Java 25 LTS (Temurin or similar). The Maven wrapper is committed, so a separate Maven install is optional.
- Node.js 24 LTS and npm
- Docker with Docker Compose, for local PostgreSQL
- Later, for infrastructure work only: AWS CLI v2 and the AWS CDK CLI (`npm i -g aws-cdk`)

Basic local development and tests need no cloud credentials.

## Development commands

Nothing is runnable yet. The project is at Milestone 0, which covers documentation and repository rules only. Milestone 1 adds the commands to start the local stack, run tests, lint, and build, and lists them here.

## Status

See the Progress section in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md#progress).
