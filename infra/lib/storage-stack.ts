import { CfnOutput, Duration, RemovalPolicy, Stack, type StackProps } from 'aws-cdk-lib'
import * as s3 from 'aws-cdk-lib/aws-s3'
import * as sqs from 'aws-cdk-lib/aws-sqs'
import type { Construct } from 'constructs'

export const ENVIRONMENTS = ['dev', 'staging', 'prod'] as const
export type EnvironmentName = (typeof ENVIRONMENTS)[number]

export type StorageStackProps = StackProps & {
  environmentName: EnvironmentName
  /** The web app's origin (scheme, host, and port), the only origin allowed to upload from a browser. */
  appOrigin: string
}

/**
 * Per-environment private photo storage and the background job queue (Milestone 4; see D11 and D12).
 *
 * - The bucket is never public: all public access is blocked, ACLs are disabled, and requests without TLS are
 *   denied. Browsers reach objects only through short-lived presigned URLs issued by the backend.
 * - The job queue sends a message to the dead-letter queue after 5 receives that were never deleted, which
 *   only happens when a worker keeps crashing on it; ordinary retries are scheduled by the backend's job table.
 *
 * ECS services and their IAM grants arrive with Milestone 8.
 */
export class StorageStack extends Stack {
  readonly bucket: s3.Bucket
  readonly jobQueue: sqs.Queue
  readonly jobDeadLetterQueue: sqs.Queue

  constructor(scope: Construct, id: string, props: StorageStackProps) {
    super(scope, id, props)
    const name = `photo-space-${props.environmentName}`

    this.bucket = new s3.Bucket(this, 'Photos', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      // Versioning protects originals from accidental overwrites and deletes; old versions expire.
      versioned: true,
      lifecycleRules: [
        { id: 'expire-old-versions', noncurrentVersionExpiration: Duration.days(30) },
        { id: 'abort-incomplete-uploads', abortIncompleteMultipartUploadAfter: Duration.days(1) },
      ],
      // Browsers upload originals straight to S3 with a presigned PUT; nothing else needs CORS.
      cors: [
        {
          allowedOrigins: [props.appOrigin],
          allowedMethods: [s3.HttpMethods.PUT],
          allowedHeaders: ['content-type'],
          maxAge: 3000,
        },
      ],
      removalPolicy: RemovalPolicy.RETAIN,
    })

    this.jobDeadLetterQueue = new sqs.Queue(this, 'JobsDeadLetter', {
      queueName: `${name}-jobs-dlq`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      retentionPeriod: Duration.days(14),
    })

    this.jobQueue = new sqs.Queue(this, 'Jobs', {
      queueName: `${name}-jobs`,
      encryption: sqs.QueueEncryption.SQS_MANAGED,
      enforceSSL: true,
      // Longer than the worker's 10-minute job lease (app.jobs.worker.lease).
      visibilityTimeout: Duration.minutes(15),
      retentionPeriod: Duration.days(4),
      deadLetterQueue: { queue: this.jobDeadLetterQueue, maxReceiveCount: 5 },
    })

    new CfnOutput(this, 'PhotoBucketName', { value: this.bucket.bucketName, description: 'STORAGE_BUCKET' })
    new CfnOutput(this, 'JobQueueUrl', { value: this.jobQueue.queueUrl, description: 'JOBS_QUEUE_URL' })
    new CfnOutput(this, 'JobDeadLetterQueueUrl', { value: this.jobDeadLetterQueue.queueUrl })
  }
}

/** Reads and checks the context values that select an environment. */
export function storageStackProps(environment: unknown, appOrigin: unknown): StorageStackProps {
  if (typeof environment !== 'string' || !(ENVIRONMENTS as readonly string[]).includes(environment)) {
    throw new Error(`Set -c env=<${ENVIRONMENTS.join('|')}>`)
  }
  if (typeof appOrigin !== 'string' || !isOrigin(appOrigin)) {
    throw new Error('Set -c appOrigin=<https://host[:port]>, the web app origin allowed to upload')
  }
  return { environmentName: environment as EnvironmentName, appOrigin }
}

function isOrigin(value: string): boolean {
  try {
    const url = new URL(value)
    const local = url.hostname === 'localhost' || url.hostname === '127.0.0.1'
    return (url.protocol === 'https:' || (local && url.protocol === 'http:')) && url.origin === value
  } catch {
    return false
  }
}
