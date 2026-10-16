import { App } from 'aws-cdk-lib'
import { StorageStack, storageStackProps } from '../lib/storage-stack.ts'

// One set of stacks per environment: cdk synth -c env=dev -c appOrigin=https://dev.example.com
const app = new App()
const props = storageStackProps(app.node.tryGetContext('env'), app.node.tryGetContext('appOrigin'))

new StorageStack(app, `PhotoSpace-${props.environmentName}-Storage`, {
  ...props,
  env: { account: process.env.CDK_DEFAULT_ACCOUNT, region: process.env.CDK_DEFAULT_REGION },
  tags: { project: 'photo-space', environment: props.environmentName },
})
