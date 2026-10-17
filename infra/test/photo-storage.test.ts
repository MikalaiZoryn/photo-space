import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { Match } from 'aws-cdk-lib/assertions'
import { ACCOUNT, resource, resources, synthesize } from './support.ts'

const { app } = synthesize()
const photoBucket = () => resource(app, 'AWS::S3::Bucket', 'StoragePhotos')

describe('PhotoStorage', () => {
  it('blocks every kind of public access to the photo bucket', () => {
    assert.deepEqual(photoBucket().Properties.PublicAccessBlockConfiguration, {
      BlockPublicAcls: true,
      BlockPublicPolicy: true,
      IgnorePublicAcls: true,
      RestrictPublicBuckets: true,
    })
    assert.deepEqual(photoBucket().Properties.OwnershipControls, { Rules: [{ ObjectOwnership: 'BucketOwnerEnforced' }] })
    assert.deepEqual(photoBucket().Properties.BucketEncryption, {
      ServerSideEncryptionConfiguration: [{ ServerSideEncryptionByDefault: { SSEAlgorithm: 'AES256' } }],
    })
  })

  it('has a name fixed in advance, per environment, account, and region', () => {
    assert.equal(photoBucket().Properties.BucketName, `photo-space-staging-photos-${ACCOUNT}-us-west-2`)
  })

  it('denies requests without TLS and grants nothing to anyone', () => {
    const statements = resource(app, 'AWS::S3::BucketPolicy', 'StoragePhotosPolicy').Properties.PolicyDocument
      .Statement as Record<string, unknown>[]
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
    assert.deepEqual(photoBucket().Properties.CorsConfiguration, {
      CorsRules: [
        {
          AllowedOrigins: [{ 'Fn::Join': ['', ['https://', { 'Fn::GetAtt': [cdnId(), 'DomainName'] }]] }],
          AllowedMethods: ['PUT'],
          AllowedHeaders: ['content-type'],
          MaxAge: 3000,
        },
      ],
    })
  })

  it('keeps the bucket when the stack is deleted and cleans up incomplete uploads', () => {
    const bucket = photoBucket()
    assert.equal(bucket.DeletionPolicy, 'Retain')
    assert.equal(bucket.UpdateReplacePolicy, 'Retain')
    assert.deepEqual(bucket.Properties.VersioningConfiguration, { Status: 'Enabled' })
    const rules = bucket.Properties.LifecycleConfiguration.Rules as Record<string, unknown>[]
    assert.ok(rules.some((rule) => JSON.stringify(rule.NoncurrentVersionExpiration) === '{"NoncurrentDays":30}'))
    assert.ok(rules.some((rule) => JSON.stringify(rule.AbortIncompleteMultipartUpload) === '{"DaysAfterInitiation":1}'))
  })

  it('sends repeatedly failing job messages to a dead-letter queue', () => {
    assert.equal(resources(app, 'AWS::SQS::Queue').length, 2)
    app.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'photo-space-staging-jobs',
      VisibilityTimeout: 900,
      SqsManagedSseEnabled: true,
      RedrivePolicy: { maxReceiveCount: 5, deadLetterTargetArn: Match.anyValue() },
    })
    app.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: 'photo-space-staging-jobs-dlq',
      MessageRetentionPeriod: 1209600,
    })
    assert.equal(resources(app, 'AWS::SQS::QueuePolicy').length, 2) // enforceSSL on both
  })
})

function cdnId(): string {
  const [[id]] = resources(app, 'AWS::CloudFront::Distribution')
  return id
}
