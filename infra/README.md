# infra

The AWS CDK app, written in TypeScript (see `docs/decisions.md`, D1, D13, and D18). It runs on Node 24's built-in TypeScript support, so there is no build step. How to deploy, roll back, and restore is in [`docs/operations.md`](../docs/operations.md).

## Stacks

Per environment (`staging`, `prod`; settings in `lib/environments.ts`):

- `PhotoSpace-<env>` (`lib/photo-space-stack.ts`, us-west-2):
  - **Network:** a VPC across two availability zones with public and isolated subnets and no NAT gateway, plus an S3 gateway endpoint.
  - **Edge:** CloudFront serves the frontend from a private bucket (origin access control) and forwards `/api/*` uncached to an internal load balancer through a VPC origin. It adds security headers (HSTS, CSP, frame and type options) and uses the edge stack's firewall.
  - **Services:** an ECS cluster with the `api` and `worker` Fargate services, both from `backend/Dockerfile`, each with its own least-privilege task role, a read-only root file system, health checks, and circuit-breaker rollback. A `db-tools` task definition runs psql in the VPC for restore tests.
  - **Data:** RDS PostgreSQL 18 (encrypted, TLS only, point-in-time restore, final snapshot on deletion) and the private photo bucket and job queues (`lib/photo-storage.ts`, D11 and D12). The database password and the Google OAuth client are in Secrets Manager.
  - **Monitoring** (`lib/monitoring.ts`): log groups, metric filters on the backend's JSON logs, alarms, and the `photo-space-<env>-alarms` SNS topic.
- `PhotoSpace-<env>-Edge` (`lib/edge-stack.ts`, us-east-1): the CloudFront WAF web ACL with per-IP rate limits and AWS managed rules.

Once per account: `PhotoSpace-GitHubDeploy` (`lib/github-deploy-stack.ts`), the role that the GitHub `Deploy` workflow assumes through OIDC.

The photo bucket is private: all public access is blocked, ACLs are disabled, non-TLS requests are denied, and objects use S3-managed encryption. It is versioned, and old versions expire after 30 days. Incomplete multipart uploads are aborted after 1 day. CORS allows only `PUT` with `content-type` from the app's CloudFront origin. The bucket is kept if the stack is deleted. The job queue `photo-space-<env>-jobs` has a 15-minute visibility timeout (longer than the worker's 10-minute lease); after 5 receives that were never deleted, a message moves to `photo-space-<env>-jobs-dlq`, which keeps messages for 14 days.

## Commands

```bash
npm ci
npm run typecheck
npm test                                                     # CDK assertions with node --test
(cd ../frontend && npm ci && npm run build)                  # synthesis uploads frontend/dist
npx cdk synth -q -c env=staging                              # writes cdk.out/, needs no AWS credentials or Docker
```

`-c env=staging|prod` is required. Deploying needs AWS credentials, Docker (to build the backend image), and a bootstrapped account: see [`docs/operations.md`](../docs/operations.md). Nothing has been deployed yet.

`scripts/restore-test.sh <env>` runs the database restore test against a deployed environment.
