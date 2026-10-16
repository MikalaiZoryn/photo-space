import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'
import { App } from 'aws-cdk-lib'
import { Match, Template } from 'aws-cdk-lib/assertions'
import { StorageStack, storageStackProps } from '../lib/storage-stack.ts'

// The same feature flags as `cdk synth`, so the tested template is the deployed one.
const { context } = JSON.parse(readFileSync(new URL('../cdk.json', import.meta.url), 'utf8')) as {
  context: Record<string, unknown>
}

function template(appOrigin = 'https://app.example.com') {
  const app = new App({ context })
  const stack = new StorageStack(app, 'Test', { environmentName: 'staging', appOrigin })
  return Template.fromStack(stack)
}

describe('StorageStack', () => {
  it('blocks every kind of public access to the photo bucket', () => {
    template().hasResourceProperties('AWS::S3::Bucket', {
      PublicAccessBlockConfiguration: {
        BlockPublicAcls: true,
        BlockPublicPolicy: true,
        IgnorePublicAcls: true,
        RestrictPublicBuckets: true,
      },
      OwnershipControls: { Rules: [{ ObjectOwnership: 'BucketOwnerEnforced' }] },
      BucketEncryption: {
        ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }],
      },
    })
  })

  it('denies requests without TLS and grants nothing to anyone', () => {
    const policies = template().findResources('AWS::S3::BucketPolicy')
    const statements = Object.values(policies).flatMap(
      (policy) => (policy as { Properties: { PolicyDocument: { Statement: Record<string, unknown>[] } } }).Properties
        .PolicyDocument.Statement,
    )
    assert.ok(statements.length > 0)
    for (const statement of statements) {
      assert.equal(statement.Effect, 'Deny', 'the bucket policy only denies')
    }
    assert.ok(
      statements.some(
        (statement) =>
          JSON.stringify(statement.Condition) === JSON.stringify({ Bool: { 'aws:SecureTransport': 'false' } }),
      ),
    )
  })

  it('allows browser uploads only from the app origin, only with PUT', () => {
    template('https://photos.example.com').hasResourceProperties('AWS::S3::Bucket', {
      CorsConfiguration: {
        CorsRules: [
          {
            AllowedOrigins: ['https://photos.example.com'],
            AllowedMethods: ['PUT'],
            AllowedHeaders: ['content-type'],
          },
        ],
      },
    })
  })

  it('keeps the bucket when the stack is deleted and cleans up incomplete uploads', () => {
    const t = template()
    t.hasResource('AWS::S3::Bucket', { DeletionPolicy: 'Retain', UpdateReplacePolicy: 'Retain' })
    t.hasResourceProperties('AWS::S3::Bucket', {
      VersioningConfiguration: { Status: 'Enabled' },
      LifecycleConfiguration: {
        Rules: Match.arrayWith([
          Match.objectLike({ NoncurrentVersionExpiration: { NoncurrentDays: 30 }, Status: 'Enabled' }),
          Match.objectLike({ AbortIncompleteMultipartUpload: { DaysAfterInitiation: 1 }, Status: 'Enabled' }),
        ]),
      },
    })
  })

  it('sends repeatedly failing job messages to a dead-letter queue', () => {
    const t = template()
    t.resourceCountIs('AWS::SQS::Queue', 2)
    t.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'photo-space-staging-jobs',
      VisibilityTimeout: 900,
      SqsManagedSseEnabled: true,
      RedrivePolicy: { maxReceiveCount: 5, deadLetterTargetArn: Match.anyValue() },
    })
    t.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'photo-space-staging-jobs-dlq',
      MessageRetentionPeriod: 1209600,
    })
    t.resourceCountIs('AWS::SQS::QueuePolicy', 2) // enforceSSL on both
  })
})

describe('storageStackProps', () => {
  it('accepts a known environment and an exact origin', () => {
    assert.deepEqual(storageStackProps('prod', 'https://app.example.com'), {
      environmentName: 'prod',
      appOrigin: 'https://app.example.com',
    })
    assert.equal(storageStackProps('dev', 'http://localhost:5173').appOrigin, 'http://localhost:5173')
  })

  it('rejects unknown environments and anything that is not a bare origin', () => {
    assert.throws(() => storageStackProps(undefined, 'https://app.example.com'), /env=/)
    assert.throws(() => storageStackProps('production', 'https://app.example.com'), /env=/)
    for (const origin of [undefined, '*', 'app.example.com', 'http://app.example.com', 'https://app.example.com/', 'https://app.example.com/path']) {
      assert.throws(() => storageStackProps('dev', origin), /appOrigin=/, String(origin))
    }
  })
})
