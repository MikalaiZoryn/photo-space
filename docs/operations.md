# Operations runbook

How to deploy, roll back, restore, and look after a deployed environment (Milestone 8; see D18 in `decisions.md`). The infrastructure is in `infra/` (AWS CDK). The commands below run from the repository root unless a directory is shown.

> **Status (2026-10-17):** written and checked against the synthesized templates, but **nothing has been deployed yet**. The first real staging deploy and restore test will confirm or correct these steps. Until then, treat this runbook as unverified.

## What gets deployed

Each environment (`staging`, `prod`) is two CloudFormation stacks:

| Stack | Region | Contents |
| --- | --- | --- |
| `PhotoSpace-<env>-Edge` | us-east-1 | The CloudFront web ACL (WAF): per-IP rate limits and AWS managed rules. CloudFront accepts a web ACL only from us-east-1. |
| `PhotoSpace-<env>` | us-west-2 | VPC, internal load balancer, ECS cluster with the `api` and `worker` services, RDS PostgreSQL 18, the photo bucket and job queues, the frontend bucket, CloudFront, secrets, log groups, metric filters, alarms, and the `db-tools` task. |

`PhotoSpace-GitHubDeploy` (us-west-2) is deployed once per account and holds the role the GitHub deploy workflow assumes.

```text
Browser ──HTTPS──> CloudFront (WAF) ──/*──────> S3 frontend bucket (private, origin access control)
                        │
                        └──/api/*──VPC origin──> internal ALB (isolated subnets) ──> api tasks (public subnets)
                                                                                     │
api and worker tasks ──> RDS PostgreSQL (isolated subnets, TLS only)                 │
                    ──> photo bucket (private; browsers use presigned URLs) <────────┘
                    ──> SQS job queue ──> DLQ
```

Fargate tasks run in public subnets with public IP addresses because there is no NAT gateway. Their security groups accept nothing from the internet: the API accepts port 8080 from the load balancer only, the worker accepts nothing. The load balancer and the database have no route to the internet at all.

## Costs (staging, approximate, us-west-2)

About $90 a month while it runs: load balancer ~$17, API task (0.5 vCPU, 1 GB) ~$18, worker task (0.5 vCPU, 2 GB) ~$21, RDS db.t4g.micro with 20 GB ~$14, WAF ~$10, public IPv4 addresses ~$7, and a few dollars of CloudWatch, Secrets Manager, and CloudFront. Prod (Multi-AZ database, two API tasks) costs more. Tear staging down when it is not needed (see [Teardown](#teardown)).

## First deploy of an environment

Prerequisites: the AWS CLI v2, Docker, Node.js 24, and credentials for the target account (`aws sts get-caller-identity` works).

1. **Bootstrap CDK** in both regions, once per account:

   ```bash
   cd infra && npm ci
   npx cdk bootstrap aws://<account>/us-west-2 aws://<account>/us-east-1 -c env=staging
   ```

2. **Build the frontend.** The stack uploads `frontend/dist`, and synthesis fails without it:

   ```bash
   (cd frontend && npm ci && npm run build)
   ```

3. **Deploy both stacks.** CDK builds the backend image from `backend/Dockerfile` and pushes it to the bootstrap repository:

   ```bash
   cd infra && npx cdk deploy PhotoSpace-staging-Edge PhotoSpace-staging -c env=staging
   ```

   The first deploy takes about 20 minutes, mostly for the database and CloudFront. The output `AppUrl` is the app's address, `https://<id>.cloudfront.net`.

4. **Store the Google OAuth client.** Sign-in is off until this is done. The secret starts with empty values. Store the client without printing it, for example from a git-ignored `.env`:

   ```bash
   set -a; source .env; set +a
   jq -n --arg id "$GOOGLE_CLIENT_ID" --arg secret "$GOOGLE_CLIENT_SECRET" '{clientId: $id, clientSecret: $secret}' |
     aws secretsmanager put-secret-value --region us-west-2 --secret-id photo-space-staging/google-oauth --secret-string file:///dev/stdin
   ```

   In Google Cloud Console, add `<AppUrl>/api/auth/oauth2/callback/google` to the client's authorized redirect URIs. Then restart the API so it reads the secret (see [Restart the services](#restart-the-services)).

   Never change the secret's value in `infra/` code: CloudFormation would overwrite what you stored.

5. **Subscribe to alarms**, for example by email (AWS sends a confirmation link):

   ```bash
   aws sns subscribe --region us-west-2 --topic-arn <AlarmTopicArn> --protocol email --notification-endpoint <address>
   ```

6. **Check the deployment** with the steps in [Verify a deployment](#verify-a-deployment).

## Deploy a change

From a workstation, repeat steps 2 and 3 of the first deploy. With GitHub Actions:

1. Once per account: `cd infra && npx cdk deploy PhotoSpace-GitHubDeploy -c env=staging`. If the account already has an IAM identity provider for `token.actions.githubusercontent.com`, add `-c githubOidcProviderArn=<its ARN>`.
2. In the repository settings, create the GitHub environments `staging` and `prod` (add required reviewers to `prod`), and set the environment variable `AWS_DEPLOY_ROLE_ARN` to the stack's `DeployRoleArn` output.
3. Run the **Deploy** workflow from the Actions tab and pick the environment and the branch or tag. It runs all CI checks first and deploys only if they pass. Deploys of one environment never run at the same time.

Each deploy builds a new image, registers new task definitions, and rolls the services: new tasks start, must pass their health checks, and only then do old tasks stop (minimum healthy 100%).

Database migrations run when the new tasks start (Flyway takes a PostgreSQL advisory lock, so the API and worker can start together). Old tasks keep running during the rollout, so every migration must work with the previous release: add columns and tables first, and remove old ones in a later release (expand, then contract).

## Health checks

- The load balancer checks `GET /actuator/health/readiness` on each API task every 15 seconds. Two passes make a task healthy and three failures unhealthy. Checks start counting 120 seconds after a task starts.
- ECS checks `GET /actuator/health/liveness` inside every API and worker container every 30 seconds, after a 90-second start period. A container that fails 3 checks is replaced.
- `/actuator` is not routed through CloudFront, so these endpoints are not public. `GET /api/health` (database UP or DOWN) is public.

## Rollback

**Automatic.** Both services use the ECS deployment circuit breaker. If new tasks keep failing to start or to pass their health checks, ECS stops the deployment and returns to the last working task definition. `cdk deploy` then fails, and CloudFormation rolls the stack back to the previous template.

**Manual, to a known-good release.** Deploy the earlier commit or tag the same way as any change: run the Deploy workflow on that ref, or check it out and run step 3. This rebuilds that release's image and frontend.

**Manual, fastest (API or worker only).** Point a service back at its previous task definition revision:

```bash
aws ecs list-task-definitions --region us-west-2 --family-prefix photo-space-staging-api --sort DESC --max-items 3
aws ecs update-service --region us-west-2 --cluster photo-space-staging --service api --task-definition photo-space-staging-api:<revision>
```

The next `cdk deploy` replaces this again. The frontend is not rolled back this way.

**Migrations are not rolled back.** Flyway only moves forward. Because migrations follow expand-then-contract, the previous release runs against the newer schema. If a migration itself is wrong, fix it with a new migration. Restoring the database is a last resort, because it loses everything written since the restore point.

## Restart the services

New tasks read secrets when they start. After changing a secret, restart:

```bash
aws ecs update-service --region us-west-2 --cluster photo-space-staging --service api --force-new-deployment
aws ecs update-service --region us-west-2 --cluster photo-space-staging --service worker --force-new-deployment
```

## Backups and restore

- RDS takes a daily snapshot in the backup window (09:00–09:30 UTC) and keeps transaction logs, so the database can be restored to any second in the retention period: 7 days for staging, 30 for prod. The latest restorable time is usually less than 5 minutes ago.
- Deleting the stack leaves a final snapshot. Prod also has deletion protection.
- Photos: the bucket is versioned, and deleted or overwritten objects stay restorable for 30 days. The bucket is kept if the stack is deleted.

### Restore test

Run this after the first deploy, after database changes to the stack, and at least every quarter:

```bash
infra/scripts/restore-test.sh staging
```

It restores the database to its latest restorable time as a new instance (`photo-space-staging-db-restore-<time>`) in the same subnets and security group. It counts rows in the main tables on the live and the restored database, using the `db-tools` task (psql in the VPC), prints both, and deletes the copy, even on failure. It never reads row contents. It takes about 15–30 minutes. Record the result in `IMPLEMENTATION_PLAN.md`. Counts can differ if something was written after the restore point.

### Recover from data loss

1. Pick the restore time, just before the damage, in UTC.
2. Stop writes: `aws ecs update-service … --service api --desired-count 0` and the same for `worker`.
3. Restore to a new instance (as in the script, with `--restore-time <time>` instead of `--use-latest-restorable-time`). Wait until it is available.
4. Swap names, so the restored database gets the original endpoint that the services use:

   ```bash
   aws rds modify-db-instance --region us-west-2 --db-instance-identifier photo-space-staging-db --new-db-instance-identifier photo-space-staging-db-damaged --apply-immediately
   # wait until the rename finishes
   aws rds modify-db-instance --region us-west-2 --db-instance-identifier <restored> --new-db-instance-identifier photo-space-staging-db --apply-immediately
   ```

5. Start the services again with their usual desired counts, and check the app.
6. Keep `-damaged` until you are sure, then delete it. Run `cdk diff` and deploy, so the stack's settings (backups, deletion protection, parameter group) apply to the restored instance.

This procedure has not been rehearsed. Rehearse it on staging before relying on it for prod.

## Secrets and rotation

| Secret | Contents | Used by |
| --- | --- | --- |
| `photo-space-<env>/database` | Database user and password, generated by CloudFormation | API, worker, db-tools |
| `photo-space-<env>/google-oauth` | Google OAuth `clientId` and `clientSecret`, stored by an operator | API |

ECS injects them as environment variables when a task starts. They are not in the image, the task definition, the frontend, or the logs. The image contains only the code and the public RDS certificate bundle.

- **Google client secret:** create a new secret in Google Cloud Console, store it (first deploy, step 4), restart the API, check that sign-in works, then delete the old secret in Google.
- **Database password:** rotation is manual, because running tasks keep the password they started with. Pick a quiet time. Set a new password with `aws rds modify-db-instance --master-user-password … --apply-immediately` and store the same value in the database secret (keep its other fields). Then restart both services. New connections fail between the password change and the restart, so do the steps back to back.
- **Anthropic API key:** staging and prod use the mock content provider (no key). Using the Claude API there needs a new secret and the `CONTENT_PROVIDER` and `ANTHROPIC_API_KEY` settings on the worker; that is not built yet.

## Monitoring and alarms

Logs go to CloudWatch Logs: `/photo-space-<env>/api`, `/photo-space-<env>/worker`, and `/photo-space-<env>/db-tools`, kept for 30 days (staging) or 90 days (prod). The backend writes one JSON object per line (Elastic Common Schema) with the correlation ID, so you can search by `correlationId` from an error response. Logs contain IDs, not secrets, tokens, URLs, or email bodies.

Every alarm notifies the `photo-space-<env>-alarms` topic when it fires and when it clears.

| Alarm | Fires when | First step |
| --- | --- | --- |
| `api-5xx` | 5+ API responses with status 5xx in 5 minutes | Search the API logs for level ERROR around that time. |
| `load-balancer-5xx` | 5+ load balancer 502/503/504 in 5 minutes | No healthy API task: check the ECS service events and task logs. |
| `api-unhealthy` | No healthy API task for 3 minutes | As above; check recent deploys and database health. |
| `api-errors`, `worker-errors` | Any ERROR log line | Read the log line and its correlation ID. |
| `api-not-running`, `worker-not-running` | A service reported no CPU data for 10 minutes | The service has no running tasks: check its events and desired count. |
| `job-dispatch-failures` | The API failed to send due jobs to SQS 3+ times in 5 minutes | Check SQS and the API task role. |
| `worker-poll-failures` | The worker failed to read SQS 3+ times in 5 minutes | Check SQS and the worker task role. |
| `jobs-failed` | A job failed on its last attempt | See [Failed jobs](#failed-jobs). |
| `job-retries` | 10+ failed job attempts in 15 minutes | Processing or a provider is failing; read the worker logs. |
| `job-queue-stalled` | The oldest job message is over 10 minutes old, for 10 minutes | The worker is down or overloaded. |
| `job-dead-letters` | A message is in the dead-letter queue | See [Dead-letter queue](#dead-letter-queue). |
| `database-cpu`, `database-storage` | CPU above 80% for 15 minutes; under 2 GiB free | Check slow queries; storage autoscaling may be at its maximum. |

WAF metrics (blocked requests per rule) are in CloudWatch under `AWS/WAFV2` in us-east-1. They have no alarms.

## Failed jobs

A job that fails 5 times is `FAILED` in `background_jobs`, and its feature records the outcome. Owners see failed jobs on the **Automation** page. Recover from the feature: retry the automation run or the content batch, or upload the photo again. Each feature knows how to redo its work safely (D17).

## Dead-letter queue

A message reaches the DLQ only when handling it kept crashing the worker or the database was unreachable. The job row stays unfinished, and the dispatcher sends it again after 15 minutes. Moving DLQ messages back is always safe, because workers ignore messages for finished jobs:

```bash
aws sqs start-message-move-task --region us-west-2 --source-arn <JobDeadLetterQueueArn> --destination-arn <JobQueueArn>
```

## Verify a deployment

```bash
url=<AppUrl>
curl -s "$url/api/health"                         # {"status":"UP","database":"UP"}
curl -sI "$url/" | grep -i -E 'strict-transport|content-security|x-frame'
curl -sI "$url/embed/inquiry/x" | grep -i -E 'frame'    # frame-ancestors * and no X-Frame-Options (D19)
curl -sI "$url/inquiry/x" | grep -i 'x-frame'            # X-Frame-Options: DENY
curl -s -o /dev/null -w '%{http_code}\n' "$url/actuator/health"   # not routed to the API: 403 from S3
curl -s -o /dev/null -w '%{http_code}\n' "https://<PhotoBucketName>.s3.us-west-2.amazonaws.com/"  # 403
aws s3api get-public-access-block --bucket <PhotoBucketName>      # all four settings true
```

Then sign in, create a workspace, upload a photo, publish a gallery, open its share link in a private window, and check the browser console for CSP errors.

## Teardown

```bash
cd infra && npx cdk destroy PhotoSpace-staging PhotoSpace-staging-Edge -c env=staging
```

This leaves a final database snapshot, the photo bucket, and its contents (delete them by hand when they are no longer needed). Prod's database has deletion protection, which must be turned off first.
