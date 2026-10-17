import { CfnOutput, Duration, RemovalPolicy, SecretValue, Stack, type StackProps } from 'aws-cdk-lib'
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront'
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins'
import * as ec2 from 'aws-cdk-lib/aws-ec2'
import * as ecs from 'aws-cdk-lib/aws-ecs'
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2'
import * as iam from 'aws-cdk-lib/aws-iam'
import * as logs from 'aws-cdk-lib/aws-logs'
import * as rds from 'aws-cdk-lib/aws-rds'
import * as s3 from 'aws-cdk-lib/aws-s3'
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment'
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager'
import type { Construct } from 'constructs'
import type { EnvironmentConfig } from './environments.ts'
import { Monitoring } from './monitoring.ts'
import { PhotoStorage } from './photo-storage.ts'

export type PhotoSpaceStackProps = StackProps & {
  config: EnvironmentConfig
  /** The CloudFront web ACL from the us-east-1 edge stack. */
  webAclArn: string
  /** The backend image (backend/Dockerfile), the same for both services. */
  backendImage: ecs.ContainerImage
  /** The built frontend (frontend/dist). */
  frontendAssets: s3deploy.ISource
}

const API_PORT = 8080
const DATABASE_NAME = 'photospace'
/** The container health check: GET the liveness probe with bash alone, since the JRE image has no curl. */
const LIVENESS_CHECK = [
  'CMD',
  'bash',
  '-c',
  `exec 3<>/dev/tcp/127.0.0.1/${API_PORT} && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200 '`,
]

/**
 * One deployed environment (Milestone 8; see D18):
 *
 * - **Network:** two availability zones without NAT gateways. Fargate tasks run in public subnets with public
 *   IP addresses for outbound calls (Google, the Claude API, AWS APIs) and accept traffic only from the load
 *   balancer. The load balancer and the database are in isolated subnets with no route to the internet.
 * - **Edge:** CloudFront terminates TLS and is the only way in. It serves the frontend from a private bucket
 *   and forwards `/api/*` to the internal load balancer through a VPC origin. The edge stack's firewall rate-limits.
 * - **Services:** the same image runs as the API service (dispatcher on, worker off) and the worker service
 *   (worker and upload cleanup on), each with its own least-privilege task role. Deployments roll back on
 *   failed health checks.
 * - **Data:** PostgreSQL on RDS (encrypted, TLS only, daily backups with point-in-time restore), the private
 *   photo bucket, and the job queues. Secrets come from Secrets Manager when a task starts.
 */
export class PhotoSpaceStack extends Stack {
  constructor(scope: Construct, id: string, props: PhotoSpaceStackProps) {
    super(scope, id, props)
    const { config } = props
    const name = `photo-space-${config.name}`
    const isProd = config.name === 'prod'
    const logRemoval = isProd ? RemovalPolicy.RETAIN : RemovalPolicy.DESTROY

    // --- Network ---
    const vpc = new ec2.Vpc(this, 'Network', {
      maxAzs: 2,
      natGateways: 0,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'isolated', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    })
    // Keeps task traffic to S3 inside AWS's network, at no cost.
    vpc.addGatewayEndpoint('S3', { service: ec2.GatewayVpcEndpointAwsService.S3 })
    const taskSubnets = { subnetType: ec2.SubnetType.PUBLIC }
    const isolatedSubnets = { subnetType: ec2.SubnetType.PRIVATE_ISOLATED }

    // --- Edge, part 1: the distribution's domain is the app origin, which the photo bucket's CORS rule needs ---
    const loadBalancerSecurityGroup = new ec2.SecurityGroup(this, 'LoadBalancerSecurityGroup', {
      vpc,
      description: 'Internal API load balancer: HTTP from CloudFront VPC origin interfaces only',
      allowAllOutbound: false,
    })
    // CloudFront's VPC origin reaches the load balancer through network interfaces inside this VPC.
    loadBalancerSecurityGroup.addIngressRule(ec2.Peer.ipv4(vpc.vpcCidrBlock), ec2.Port.tcp(80), 'CloudFront VPC origin')
    const loadBalancer = new elbv2.ApplicationLoadBalancer(this, 'LoadBalancer', {
      vpc,
      vpcSubnets: isolatedSubnets,
      internetFacing: false,
      securityGroup: loadBalancerSecurityGroup,
      dropInvalidHeaderFields: true,
    })

    const site = new s3.Bucket(this, 'Site', {
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      encryption: s3.BucketEncryption.S3_MANAGED,
      enforceSSL: true,
      // Only the built frontend, which every deploy uploads again.
      removalPolicy: RemovalPolicy.DESTROY,
      autoDeleteObjects: true,
    })

    // The photo bucket's CORS rule needs the distribution's domain, and the distribution's CSP needs the bucket's
    // host, so the bucket gets a name fixed in advance. Presigned URLs use this virtual-hosted form.
    const photoBucketName = `${name}-photos-${this.account}-${this.region}`
    const photoHost = `${photoBucketName}.s3.${this.region}.amazonaws.com`
    const securityHeaders = new cloudfront.ResponseHeadersPolicy(this, 'SecurityHeaders', {
      responseHeadersPolicyName: `${name}-security-headers`,
      securityHeadersBehavior: {
        strictTransportSecurity: { accessControlMaxAge: Duration.days(365), includeSubdomains: true, override: true },
        contentTypeOptions: { override: true },
        frameOptions: { frameOption: cloudfront.HeadersFrameOption.DENY, override: true },
        referrerPolicy: { referrerPolicy: cloudfront.HeadersReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN, override: true },
        contentSecurityPolicy: {
          // Photos load from presigned S3 URLs and uploads PUT to them; everything else is this origin.
          contentSecurityPolicy: [
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            `img-src 'self' data: blob: https://${photoHost}`,
            `connect-src 'self' https://${photoHost}`,
            "font-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
          ].join('; '),
          override: true,
        },
      },
    })

    const distribution = new cloudfront.Distribution(this, 'Cdn', {
      comment: `${name} web app and API`,
      defaultRootObject: 'index.html',
      priceClass: cloudfront.PriceClass.PRICE_CLASS_100,
      httpVersion: cloudfront.HttpVersion.HTTP2_AND_3,
      webAclId: props.webAclArn,
      defaultBehavior: {
        origin: origins.S3BucketOrigin.withOriginAccessControl(site),
        viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
        cachePolicy: cloudfront.CachePolicy.CACHING_OPTIMIZED,
        responseHeadersPolicy: securityHeaders,
      },
      additionalBehaviors: {
        '/api/*': {
          origin: origins.VpcOrigin.withApplicationLoadBalancer(loadBalancer, {
            vpcOriginName: `${name}-api`,
            protocolPolicy: cloudfront.OriginProtocolPolicy.HTTP_ONLY,
            httpPort: 80,
            readTimeout: Duration.seconds(30),
          }),
          viewerProtocolPolicy: cloudfront.ViewerProtocolPolicy.HTTPS_ONLY,
          allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
          cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
          // Host, cookies, the query string, and CloudFront-Forwarded-Proto, which the backend's aws profile
          // uses to build absolute URLs (the Google redirect URI).
          originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_AND_CLOUDFRONT_2022,
          responseHeadersPolicy: securityHeaders,
        },
      },
    })
    const appOrigin = `https://${distribution.distributionDomainName}`
    const storage = new PhotoStorage(this, 'Storage', { environmentName: config.name, bucketName: photoBucketName, appOrigin })

    // index.html must never be cached for long; the hashed files under assets/ can be cached forever.
    new s3deploy.BucketDeployment(this, 'SiteAssets', {
      destinationBucket: site,
      sources: [props.frontendAssets],
      exclude: ['index.html'],
      cacheControl: [s3deploy.CacheControl.fromString('public, max-age=31536000, immutable')],
      memoryLimit: 512,
    })
    new s3deploy.BucketDeployment(this, 'SiteIndex', {
      destinationBucket: site,
      sources: [props.frontendAssets],
      exclude: ['*'],
      include: ['index.html'],
      prune: false,
      cacheControl: [s3deploy.CacheControl.fromString('no-cache')],
      distribution,
      distributionPaths: ['/index.html', '/'],
      memoryLimit: 512,
    })

    // --- Database ---
    const databaseSecurityGroup = new ec2.SecurityGroup(this, 'DatabaseSecurityGroup', {
      vpc,
      description: 'PostgreSQL: from the API, worker, and database tools tasks only',
      allowAllOutbound: false,
    })
    const database = new rds.DatabaseInstance(this, 'Database', {
      instanceIdentifier: `${name}-db`,
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_18 }),
      instanceType: new ec2.InstanceType(config.database.instanceType),
      vpc,
      vpcSubnets: isolatedSubnets,
      securityGroups: [databaseSecurityGroup],
      databaseName: DATABASE_NAME,
      credentials: rds.Credentials.fromGeneratedSecret('photospace', { secretName: `${name}/database` }),
      parameters: { 'rds.force_ssl': '1' },
      caCertificate: rds.CaCertificate.RDS_CA_RSA2048_G1,
      storageEncrypted: true,
      storageType: rds.StorageType.GP3,
      allocatedStorage: config.database.allocatedStorageGiB,
      maxAllocatedStorage: config.database.maxAllocatedStorageGiB,
      multiAz: config.database.multiAz,
      publiclyAccessible: false,
      autoMinorVersionUpgrade: true,
      // UTC; early morning in the Americas, before the European working day.
      preferredBackupWindow: '09:00-09:30',
      preferredMaintenanceWindow: 'sun:10:00-sun:10:30',
      backupRetention: Duration.days(config.database.backupRetentionDays),
      copyTagsToSnapshot: true,
      deletionProtection: config.database.deletionProtection,
      // Deleting the stack leaves a final snapshot.
      removalPolicy: RemovalPolicy.SNAPSHOT,
    })
    const databaseSecret = database.secret!
    const databaseUrl =
      `jdbc:postgresql://${database.dbInstanceEndpointAddress}:${database.dbInstanceEndpointPort}/${DATABASE_NAME}` +
      '?sslmode=verify-full&sslrootcert=/app/rds-ca.pem'

    // Sign-in stays off until an operator stores the OAuth client here (docs/operations.md). Never change this
    // value in code: CloudFormation would overwrite what the operator stored.
    const googleSecret = new secretsmanager.Secret(this, 'GoogleOAuth', {
      secretName: `${name}/google-oauth`,
      description: 'Google OAuth client for sign-in: clientId and clientSecret. Set by an operator.',
      secretObjectValue: { clientId: SecretValue.unsafePlainText(''), clientSecret: SecretValue.unsafePlainText('') },
    })

    // --- Services ---
    const cluster = new ecs.Cluster(this, 'Cluster', {
      clusterName: name,
      vpc,
      containerInsightsV2: ecs.ContainerInsights.DISABLED,
    })
    const sharedEnvironment = {
      SPRING_PROFILES_ACTIVE: 'aws',
      AWS_REGION: this.region,
      DB_URL: databaseUrl,
      STORAGE_BUCKET: storage.bucket.bucketName,
      JOBS_QUEUE_URL: storage.jobQueue.queueUrl,
    }
    const databaseSecrets = {
      DB_USERNAME: ecs.Secret.fromSecretsManager(databaseSecret, 'username'),
      DB_PASSWORD: ecs.Secret.fromSecretsManager(databaseSecret, 'password'),
    }
    const photos = (pattern: string) => storage.bucket.arnForObjects(pattern)
    // Without ListBucket, S3 answers 403 instead of 404 for a missing object, and the backend relies on 404.
    const listPhotos = new iam.PolicyStatement({
      actions: ['s3:ListBucket'],
      resources: [storage.bucket.bucketArn],
      conditions: { StringLike: { 's3:prefix': ['workspaces/*'] } },
    })

    const service = (
      serviceName: 'api' | 'worker',
      size: { cpu: number; memoryMiB: number; desiredCount: number },
      environment: Record<string, string>,
      secrets: Record<string, ecs.Secret>,
      taskPolicy: iam.PolicyStatement[],
      serviceOptions: Partial<ecs.FargateServiceProps> = {},
    ) => {
      const logGroup = new logs.LogGroup(this, `${serviceName}Logs`, {
        logGroupName: `/${name}/${serviceName}`,
        retention: config.logRetentionDays,
        removalPolicy: logRemoval,
      })
      const taskRole = new iam.Role(this, `${serviceName}TaskRole`, {
        assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
        description: `${name} ${serviceName} task: application permissions`,
      })
      taskPolicy.forEach((statement) => taskRole.addToPrincipalPolicy(statement))
      const taskDefinition = new ecs.FargateTaskDefinition(this, `${serviceName}Task`, {
        family: `${name}-${serviceName}`,
        cpu: size.cpu,
        memoryLimitMiB: size.memoryMiB,
        runtimePlatform: { cpuArchitecture: ecs.CpuArchitecture.X86_64, operatingSystemFamily: ecs.OperatingSystemFamily.LINUX },
        taskRole,
        volumes: [{ name: 'tmp' }],
      })
      const container = taskDefinition.addContainer('app', {
        image: props.backendImage,
        environment: { ...sharedEnvironment, ...environment },
        secrets: { ...databaseSecrets, ...secrets },
        logging: ecs.LogDrivers.awsLogs({ logGroup, streamPrefix: serviceName }),
        portMappings: [{ containerPort: API_PORT }],
        readonlyRootFilesystem: true,
        healthCheck: {
          command: LIVENESS_CHECK,
          interval: Duration.seconds(30),
          timeout: Duration.seconds(5),
          retries: 3,
          startPeriod: Duration.seconds(90),
        },
        stopTimeout: Duration.seconds(60),
      })
      // Photo processing and Tomcat need a writable /tmp; the rest of the file system is read-only.
      container.addMountPoints({ sourceVolume: 'tmp', containerPath: '/tmp', readOnly: false })

      const securityGroup = new ec2.SecurityGroup(this, `${serviceName}SecurityGroup`, {
        vpc,
        description: `${name} ${serviceName} tasks`,
        allowAllOutbound: true,
      })
      databaseSecurityGroup.addIngressRule(securityGroup, ec2.Port.tcp(5432), `${serviceName} tasks`)
      const fargate = new ecs.FargateService(this, `${serviceName}Service`, {
        serviceName,
        cluster,
        taskDefinition,
        desiredCount: size.desiredCount,
        vpcSubnets: taskSubnets,
        assignPublicIp: true,
        securityGroups: [securityGroup],
        minHealthyPercent: 100,
        maxHealthyPercent: 200,
        circuitBreaker: { enable: true, rollback: true },
        enableExecuteCommand: false,
        ...serviceOptions,
      })
      return { fargate, logGroup, taskRole }
    }

    const api = service(
      'api',
      config.api,
      { JOBS_DISPATCHER_ENABLED: 'true', JOBS_WORKER_ENABLED: 'false', ASSETS_CLEANUP_ENABLED: 'false' },
      {
        GOOGLE_CLIENT_ID: ecs.Secret.fromSecretsManager(googleSecret, 'clientId'),
        GOOGLE_CLIENT_SECRET: ecs.Secret.fromSecretsManager(googleSecret, 'clientSecret'),
      },
      [
        // Presigned GETs for previews, thumbnails, and downloads, and HEAD to confirm uploads.
        new iam.PolicyStatement({ actions: ['s3:GetObject'], resources: [photos('workspaces/*')] }),
        // Presigned PUTs, for originals only.
        new iam.PolicyStatement({ actions: ['s3:PutObject'], resources: [photos('workspaces/*/originals/*')] }),
        listPhotos,
        new iam.PolicyStatement({ actions: ['sqs:SendMessage'], resources: [storage.jobQueue.queueArn] }),
      ],
      // Startup includes Flyway migrations; the load balancer's checks don't count until this has passed.
      { healthCheckGracePeriod: Duration.seconds(120) },
    )
    const worker = service(
      'worker',
      config.worker,
      { JOBS_DISPATCHER_ENABLED: 'false', JOBS_WORKER_ENABLED: 'true', ASSETS_CLEANUP_ENABLED: 'true' },
      {},
      [
        // Reads originals and previews, writes derivatives, deletes rejected and abandoned uploads.
        new iam.PolicyStatement({
          actions: ['s3:GetObject', 's3:PutObject', 's3:DeleteObject'],
          resources: [photos('workspaces/*')],
        }),
        listPhotos,
        new iam.PolicyStatement({
          actions: ['sqs:ReceiveMessage', 'sqs:DeleteMessage'],
          resources: [storage.jobQueue.queueArn],
        }),
      ],
    )

    const listener = loadBalancer.addListener('Http', { port: 80, open: false })
    const apiTargets = listener.addTargets('Api', {
      port: API_PORT,
      protocol: elbv2.ApplicationProtocol.HTTP,
      targets: [api.fargate],
      deregistrationDelay: Duration.seconds(30),
      healthCheck: {
        path: '/actuator/health/readiness',
        healthyHttpCodes: '200',
        interval: Duration.seconds(15),
        timeout: Duration.seconds(5),
        healthyThresholdCount: 2,
        unhealthyThresholdCount: 3,
      },
    })

    // --- Database tools: a psql task for restore tests and operations, started by hand (docs/operations.md) ---
    const toolsLogs = new logs.LogGroup(this, 'DatabaseToolsLogs', {
      logGroupName: `/${name}/db-tools`,
      retention: config.logRetentionDays,
      removalPolicy: logRemoval,
    })
    const toolsTask = new ecs.FargateTaskDefinition(this, 'DatabaseToolsTask', {
      family: `${name}-db-tools`,
      cpu: 256,
      memoryLimitMiB: 512,
    })
    toolsTask.addContainer('psql', {
      image: ecs.ContainerImage.fromRegistry('public.ecr.aws/docker/library/postgres:18'),
      entryPoint: ['psql'],
      command: ['--version'],
      environment: {
        PGHOST: database.dbInstanceEndpointAddress,
        PGPORT: database.dbInstanceEndpointPort,
        PGDATABASE: DATABASE_NAME,
        PGSSLMODE: 'require',
      },
      secrets: databaseSecrets,
      logging: ecs.LogDrivers.awsLogs({ logGroup: toolsLogs, streamPrefix: 'psql' }),
      readonlyRootFilesystem: true,
    })
    const toolsSecurityGroup = new ec2.SecurityGroup(this, 'DatabaseToolsSecurityGroup', {
      vpc,
      description: `${name} database tools tasks`,
      allowAllOutbound: true,
    })
    databaseSecurityGroup.addIngressRule(toolsSecurityGroup, ec2.Port.tcp(5432), 'database tools tasks')

    const monitoring = new Monitoring(this, 'Monitoring', {
      environmentName: config.name,
      loadBalancer,
      apiTargetGroup: apiTargets,
      apiService: api.fargate,
      workerService: worker.fargate,
      apiLogs: api.logGroup,
      workerLogs: worker.logGroup,
      jobQueue: storage.jobQueue,
      jobDeadLetterQueue: storage.jobDeadLetterQueue,
      database,
    })

    const output = (key: string, value: string, description?: string) => new CfnOutput(this, key, { value, description })
    output('AppUrl', appOrigin, 'The app. Add <AppUrl>/api/auth/oauth2/callback/google to the Google OAuth client.')
    output('DistributionId', distribution.distributionId)
    output('ClusterName', cluster.clusterName)
    output('ApiServiceName', api.fargate.serviceName)
    output('WorkerServiceName', worker.fargate.serviceName)
    output('DatabaseInstanceId', database.instanceIdentifier)
    output('DatabaseToolsTaskDefinition', toolsTask.family)
    output('DatabaseToolsSecurityGroupId', toolsSecurityGroup.securityGroupId)
    output('TaskSubnetIds', vpc.selectSubnets(taskSubnets).subnetIds.join(','))
    output('PhotoBucketName', storage.bucket.bucketName)
    output('JobQueueUrl', storage.jobQueue.queueUrl)
    output('JobDeadLetterQueueArn', storage.jobDeadLetterQueue.queueArn)
    output('JobQueueArn', storage.jobQueue.queueArn)
    output('GoogleOAuthSecretName', googleSecret.secretName)
    output('AlarmTopicArn', monitoring.topic.topicArn)
  }
}
