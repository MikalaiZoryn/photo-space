# infra

The AWS CDK app, written in TypeScript (see `docs/decisions.md`, D1 and D13). It runs on Node 24's built-in TypeScript support, so there is no build step.

## Stacks

`PhotoSpace-<env>-Storage` (`lib/storage-stack.ts`), one per environment (`dev`, `staging`, `prod`):

- **Photo bucket:** private. All public access is blocked, ACLs are disabled, non-TLS requests are denied, and objects use S3-managed encryption. It is versioned, and old versions expire after 30 days. Incomplete multipart uploads are aborted after 1 day. CORS allows only `PUT` with `content-type` from the app origin, for presigned browser uploads. The bucket is retained if the stack is deleted.
- **Job queue** `photo-space-<env>-jobs`: a 15-minute visibility timeout (longer than the worker's 10-minute lease), SQS-managed encryption, and TLS only. After 5 receives that were never deleted, a message moves to `photo-space-<env>-jobs-dlq`, which keeps messages for 14 days.

Outputs give the values the backend needs: `PhotoBucketName` is `STORAGE_BUCKET`, and `JobQueueUrl` is `JOBS_QUEUE_URL`. ECS services, IAM task roles, and their grants arrive with Milestone 8.

## Commands

```bash
npm ci
npm run typecheck
npm test                                                     # CDK assertions with node --test
npx cdk synth -c env=dev -c appOrigin=http://localhost:5173  # writes cdk.out/, needs no AWS credentials
```

Both context values are required. `appOrigin` must be a bare origin (`https://host[:port]`; `http` is accepted only for localhost). Deploying (`npx cdk deploy -c env=… -c appOrigin=…`) needs AWS credentials and a bootstrapped account, which Milestone 8 sets up. Nothing has been deployed yet.

## Dead-letter queue re-drive

A message reaches the DLQ only when handling it kept crashing the worker or the database was unreachable. The job row stays unfinished, and the dispatcher re-sends it after 15 minutes. Moving DLQ messages back is always safe, because workers ignore messages for finished jobs:

```bash
aws sqs start-message-move-task --source-arn <dlq-arn> --destination-arn <queue-arn>
```
