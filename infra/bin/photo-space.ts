import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { App } from 'aws-cdk-lib'
import { Platform } from 'aws-cdk-lib/aws-ecr-assets'
import * as ecs from 'aws-cdk-lib/aws-ecs'
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment'
import { EdgeStack } from '../lib/edge-stack.ts'
import { environmentConfig } from '../lib/environments.ts'
import { GitHubDeployStack } from '../lib/github-deploy-stack.ts'
import { PhotoSpaceStack } from '../lib/photo-space-stack.ts'

// Two stacks per environment (D18): npx cdk deploy PhotoSpace-staging-Edge PhotoSpace-staging -c env=staging
const app = new App()
const config = environmentConfig(app.node.tryGetContext('env'))
const account = process.env.CDK_DEFAULT_ACCOUNT || undefined
const tags = { project: 'photo-space', environment: config.name }

const backend = fileURLToPath(new URL('../../backend', import.meta.url))
const frontendDist = fileURLToPath(new URL('../../frontend/dist', import.meta.url))
if (!existsSync(`${frontendDist}/index.html`)) {
  throw new Error('Build the frontend first: (cd frontend && npm ci && npm run build)')
}

// CloudFront only takes a firewall from us-east-1.
const edge = new EdgeStack(app, `PhotoSpace-${config.name}-Edge`, {
  environmentName: config.name,
  env: { account, region: 'us-east-1' },
  crossRegionReferences: true,
  tags,
})

new PhotoSpaceStack(app, `PhotoSpace-${config.name}`, {
  config,
  env: { account, region: config.region },
  crossRegionReferences: true,
  webAclArn: edge.webAcl.attrArn,
  backendImage: ecs.ContainerImage.fromAsset(backend, { platform: Platform.LINUX_AMD64 }),
  frontendAssets: s3deploy.Source.asset(frontendDist),
  tags,
})

// Once per account, for the GitHub deploy workflow; not part of an environment's deploys.
new GitHubDeployStack(app, 'PhotoSpace-GitHubDeploy', {
  repository: app.node.getContext('githubRepository'),
  existingProviderArn: app.node.tryGetContext('githubOidcProviderArn'),
  env: { account, region: config.region },
  tags: { project: 'photo-space' },
})
