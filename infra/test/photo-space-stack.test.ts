import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { resource, resources, synthesize } from './support.ts'

const { app } = synthesize()
const prod = synthesize('prod').app

type Statement = { Effect: string; Action: string | string[]; Resource: unknown; Condition?: unknown }
const actions = (statement: Statement) => [statement.Action].flat()

function taskPolicy(service: 'api' | 'worker'): Statement[] {
  return resource(app, 'AWS::IAM::Policy', `${service}TaskRoleDefaultPolicy`).Properties.PolicyDocument.Statement
}

function container(family: string) {
  const [[, task]] = resources(app, 'AWS::ECS::TaskDefinition').filter(
    ([, task]) => task.Properties.Family === `photo-space-staging-${family}`,
  )
  return task.Properties.ContainerDefinitions[0] as Record<string, any>
}

const environment = (family: string) =>
  Object.fromEntries((container(family).Environment as { Name: string; Value: unknown }[]).map((e) => [e.Name, e.Value]))
const secretNames = (family: string) => (container(family).Secrets as { Name: string }[]).map((s) => s.Name).sort()

describe('task roles', () => {
  it('let the API presign reads and original uploads and send job messages, nothing else', () => {
    const statements = taskPolicy('api')
    assert.deepEqual(statements.flatMap(actions).sort(), ['s3:GetObject', 's3:ListBucket', 's3:PutObject', 'sqs:SendMessage'])
    const put = statements.find((s) => actions(s).includes('s3:PutObject'))!
    assert.match(JSON.stringify(put.Resource), /\/workspaces\/\*\/originals\/\*"/)
  })

  it('let the worker process photos and consume job messages, but not send them', () => {
    assert.deepEqual(taskPolicy('worker').flatMap(actions).sort(), [
      's3:DeleteObject',
      's3:GetObject',
      's3:ListBucket',
      's3:PutObject',
      'sqs:DeleteMessage',
      'sqs:ReceiveMessage',
    ])
  })

  it('are scoped to workspace objects, with listing limited to that prefix', () => {
    for (const service of ['api', 'worker'] as const) {
      for (const statement of taskPolicy(service)) {
        assert.equal(statement.Effect, 'Allow')
        assert.ok(!actions(statement).some((action) => action.includes('*')), `${service}: ${actions(statement)}`)
        if (actions(statement).includes('s3:ListBucket')) {
          assert.deepEqual(statement.Condition, { StringLike: { 's3:prefix': ['workspaces/*'] } })
        } else if (actions(statement).some((action) => action.startsWith('s3:'))) {
          assert.match(JSON.stringify(statement.Resource), /\/workspaces\/\*/)
        }
      }
    }
  })

  it('are separate roles for the API and the worker', () => {
    const roles = ['api', 'worker'].map((family) => JSON.stringify(
      resources(app, 'AWS::ECS::TaskDefinition').find(([, t]) => t.Properties.Family === `photo-space-staging-${family}`)![1]
        .Properties.TaskRoleArn,
    ))
    assert.notEqual(roles[0], roles[1])
  })
})

describe('services', () => {
  it('split job roles between the API and the worker', () => {
    assert.equal(environment('api').JOBS_DISPATCHER_ENABLED, 'true')
    assert.equal(environment('api').JOBS_WORKER_ENABLED, 'false')
    assert.equal(environment('api').ASSETS_CLEANUP_ENABLED, 'false')
    assert.equal(environment('worker').JOBS_DISPATCHER_ENABLED, 'false')
    assert.equal(environment('worker').JOBS_WORKER_ENABLED, 'true')
    assert.equal(environment('worker').ASSETS_CLEANUP_ENABLED, 'true')
    for (const family of ['api', 'worker']) {
      assert.equal(environment(family).SPRING_PROFILES_ACTIVE, 'aws')
      assert.match(JSON.stringify(environment(family).DB_URL), /sslmode=verify-full&sslrootcert=\/app\/rds-ca\.pem/)
    }
  })

  it('get secrets from Secrets Manager at start, never as plain environment values', () => {
    assert.deepEqual(secretNames('api'), ['DB_PASSWORD', 'DB_USERNAME', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET'])
    assert.deepEqual(secretNames('worker'), ['DB_PASSWORD', 'DB_USERNAME'])
    for (const family of ['api', 'worker', 'db-tools']) {
      for (const name of Object.keys(environment(family))) {
        assert.doesNotMatch(name, /PASSWORD|SECRET|TOKEN|KEY/, `${family}: ${name}`)
      }
    }
  })

  it('run read-only containers as health-checked, rolled-back deployments', () => {
    for (const family of ['api', 'worker']) {
      const definition = container(family)
      assert.equal(definition.ReadonlyRootFilesystem, true)
      assert.deepEqual(definition.MountPoints, [{ ContainerPath: '/tmp', ReadOnly: false, SourceVolume: 'tmp' }])
      assert.match(definition.HealthCheck.Command.join(' '), /\/actuator\/health\/liveness/)
    }
    for (const [, service] of resources(app, 'AWS::ECS::Service')) {
      assert.deepEqual(service.Properties.DeploymentConfiguration.DeploymentCircuitBreaker, { Enable: true, Rollback: true })
      assert.equal(service.Properties.DeploymentConfiguration.MinimumHealthyPercent, 100)
      assert.equal(service.Properties.EnableExecuteCommand, false)
    }
  })

  it('check API readiness at the load balancer', () => {
    const group = resource(app, 'AWS::ElasticLoadBalancingV2::TargetGroup', 'LoadBalancerHttpApiGroup').Properties
    assert.equal(group.HealthCheckPath, '/actuator/health/readiness')
    assert.equal(group.Port, 8080)
  })
})

describe('network', () => {
  it('keeps the load balancer and the database off the internet', () => {
    const balancer = resource(app, 'AWS::ElasticLoadBalancingV2::LoadBalancer', 'LoadBalancer').Properties
    assert.equal(balancer.Scheme, 'internal')
    const database = resource(app, 'AWS::RDS::DBInstance', 'Database').Properties
    assert.equal(database.PubliclyAccessible, false)
    assert.equal(resources(app, 'AWS::EC2::NatGateway').length, 0)
  })

  it('lets only the CloudFront VPC origin reach the load balancer, and only the tasks reach the database', () => {
    const balancer = resource(app, 'AWS::EC2::SecurityGroup', 'LoadBalancerSecurityGroup').Properties
    assert.equal(balancer.SecurityGroupIngress.length, 1)
    assert.equal(balancer.SecurityGroupIngress[0].FromPort, 80)
    assert.deepEqual(balancer.SecurityGroupIngress[0].CidrIp, { 'Fn::GetAtt': [Object.keys(app.findResources('AWS::EC2::VPC'))[0], 'CidrBlock'] })

    const toDatabase = resources(app, 'AWS::EC2::SecurityGroupIngress').filter(([, rule]) =>
      JSON.stringify(rule.Properties.GroupId).includes('DatabaseSecurityGroup'),
    )
    assert.deepEqual(
      toDatabase.map(([, rule]) => rule.Properties.SourceSecurityGroupId['Fn::GetAtt'][0].replace(/[0-9A-F]{8}$/, '')).sort(),
      ['DatabaseToolsSecurityGroup', 'apiSecurityGroup', 'workerSecurityGroup'],
    )
    for (const [, rule] of toDatabase) {
      assert.equal(rule.Properties.FromPort, 5432)
    }
  })
})

describe('database', () => {
  it('is encrypted, TLS only, backed up, and snapshotted when the stack is deleted', () => {
    const database = resource(app, 'AWS::RDS::DBInstance', 'Database')
    assert.equal(database.Properties.StorageEncrypted, true)
    assert.equal(database.Properties.BackupRetentionPeriod, 7)
    assert.equal(database.Properties.Engine, 'postgres')
    assert.match(String(database.Properties.EngineVersion), /^18/)
    assert.equal(database.DeletionPolicy, 'Snapshot')
    const parameters = resource(app, 'AWS::RDS::DBParameterGroup', 'DatabaseParameterGroup').Properties
    assert.equal(parameters.Parameters['rds.force_ssl'], '1')
  })

  it('is Multi-AZ, protected from deletion, and kept longer in prod', () => {
    const database = resource(prod, 'AWS::RDS::DBInstance', 'Database').Properties
    assert.equal(database.MultiAZ, true)
    assert.equal(database.DeletionProtection, true)
    assert.equal(database.BackupRetentionPeriod, 30)
  })

  it('has a generated password in Secrets Manager', () => {
    const database = resource(app, 'AWS::RDS::DBInstance', 'Database').Properties
    assert.match(JSON.stringify(database.MasterUserPassword), /resolve:secretsmanager/)
  })
})

describe('CloudFront', () => {
  const distribution = () => resource(app, 'AWS::CloudFront::Distribution', 'Cdn').Properties.DistributionConfig

  it('serves only HTTPS, with the edge firewall attached', () => {
    assert.equal(distribution().DefaultCacheBehavior.ViewerProtocolPolicy, 'redirect-to-https')
    assert.equal(distribution().CacheBehaviors[0].ViewerProtocolPolicy, 'https-only')
    assert.ok(distribution().WebACLId, 'web ACL')
  })

  it('forwards /api/* uncached to the internal load balancer, and nothing else', () => {
    const behaviors = distribution().CacheBehaviors as Record<string, any>[]
    assert.deepEqual(behaviors.map((b) => b.PathPattern), ['/api/*'])
    assert.equal(behaviors[0].CachePolicyId, '4135ea2d-6df8-44a3-9df3-4b5a84be39ad') // CachingDisabled
    assert.equal(behaviors[0].OriginRequestPolicyId, '33f36d7e-f396-46d9-90e0-52428a34d9dc') // AllViewerAndCloudFrontHeaders-2022-06
    assert.equal(resources(app, 'AWS::CloudFront::VpcOrigin').length, 1)
  })

  it('sends security headers, with a CSP that allows only this origin and the photo bucket', () => {
    const headers = resource(app, 'AWS::CloudFront::ResponseHeadersPolicy', 'SecurityHeaders').Properties
      .ResponseHeadersPolicyConfig.SecurityHeadersConfig
    assert.equal(headers.StrictTransportSecurity.AccessControlMaxAgeSec, 31536000)
    assert.equal(headers.FrameOptions.FrameOption, 'DENY')
    const csp = JSON.stringify(headers.ContentSecurityPolicy.ContentSecurityPolicy)
    assert.match(csp, /script-src 'self';/)
    assert.match(csp, /connect-src 'self' https:\/\/photo-space-staging-photos-123456789012-us-west-2\.s3\.us-west-2\.amazonaws\.com/)
    assert.match(csp, /frame-ancestors 'none'/)
  })

  it('serves the frontend from a private bucket through origin access control', () => {
    const site = resource(app, 'AWS::S3::Bucket', 'Site').Properties
    assert.equal(site.PublicAccessBlockConfiguration.RestrictPublicBuckets, true)
    const statements = resource(app, 'AWS::S3::BucketPolicy', 'SitePolicy').Properties.PolicyDocument.Statement as Statement[]
    const allows = statements.filter((s) => s.Effect === 'Allow' && actions(s).includes('s3:GetObject'))
    assert.equal(allows.length, 1)
    assert.match(JSON.stringify(allows[0]), /cloudfront\.amazonaws\.com/)
    assert.match(JSON.stringify(allows[0].Condition), /AWS:SourceArn/)
  })
})

describe('monitoring', () => {
  it('sends every alarm to the alarm topic when it fires and when it clears', () => {
    const alarms = resources(app, 'AWS::CloudWatch::Alarm')
    assert.ok(alarms.length >= 15)
    for (const [id, alarm] of alarms) {
      assert.deepEqual(alarm.Properties.AlarmActions, [{ Ref: Object.keys(app.findResources('AWS::SNS::Topic'))[0] }], id)
      assert.deepEqual(alarm.Properties.OKActions, alarm.Properties.AlarmActions, id)
    }
  })

  it('covers API failures, job failures, queue depth, and dead letters', () => {
    const names = resources(app, 'AWS::CloudWatch::Alarm').map(([, a]) => a.Properties.AlarmName as string)
    for (const name of [
      'api-5xx',
      'load-balancer-5xx',
      'api-unhealthy',
      'api-errors',
      'jobs-failed',
      'job-retries',
      'job-queue-stalled',
      'job-dead-letters',
      'worker-not-running',
      'database-storage',
    ]) {
      assert.ok(names.includes(`photo-space-staging-${name}`), name)
    }
  })

  it('counts job failures from the JSON logs of the worker', () => {
    const filter = resources(app, 'AWS::Logs::MetricFilter').find(
      ([, f]) => f.Properties.MetricTransformations[0].MetricName === 'JobsFailed',
    )![1]
    assert.equal(
      filter.Properties.FilterPattern,
      '{ $.log.logger = "com.photospace.jobs.JobRunner" && $.message = "*failed on its last attempt*" }',
    )
    assert.match(JSON.stringify(filter.Properties.LogGroupName), /workerLogs/)
  })
})
