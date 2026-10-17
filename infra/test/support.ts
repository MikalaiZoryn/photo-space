import { readFileSync } from 'node:fs'
import { App } from 'aws-cdk-lib'
import { Template } from 'aws-cdk-lib/assertions'
import * as ecs from 'aws-cdk-lib/aws-ecs'
import * as s3deploy from 'aws-cdk-lib/aws-s3-deployment'
import { EdgeStack } from '../lib/edge-stack.ts'
import { environmentConfig } from '../lib/environments.ts'
import { PhotoSpaceStack } from '../lib/photo-space-stack.ts'

// The same feature flags as `cdk synth`, so the tested templates are the deployed ones.
const { context } = JSON.parse(readFileSync(new URL('../cdk.json', import.meta.url), 'utf8')) as {
  context: Record<string, unknown>
}

export const ACCOUNT = '123456789012'

/** Both stacks of one environment, built like bin/photo-space.ts but without Docker or a frontend build. */
export function synthesize(environment = 'staging') {
  const app = new App({ context })
  const config = environmentConfig(environment)
  const edge = new EdgeStack(app, 'Edge', {
    environmentName: config.name,
    env: { account: ACCOUNT, region: 'us-east-1' },
    crossRegionReferences: true,
  })
  const stack = new PhotoSpaceStack(app, 'App', {
    config,
    env: { account: ACCOUNT, region: config.region },
    crossRegionReferences: true,
    webAclArn: edge.webAcl.attrArn,
    backendImage: ecs.ContainerImage.fromRegistry('photo-space-backend:test'),
    frontendAssets: s3deploy.Source.data('index.html', '<!doctype html>'),
  })
  return { config, edge: Template.fromStack(edge), app: Template.fromStack(stack) }
}

type Resource = { Type: string; Properties: Record<string, any>; [key: string]: unknown }

/** The resources of one type, by logical ID. */
export function resources(template: Template, type: string): [string, Resource][] {
  return Object.entries(template.findResources(type)) as [string, Resource][]
}

/** The one resource of a type whose logical ID starts with a prefix. */
export function resource(template: Template, type: string, idPrefix: string): Resource {
  const found = resources(template, type).filter(([id]) => id.startsWith(idPrefix))
  if (found.length !== 1) {
    throw new Error(`Expected one ${type} named ${idPrefix}*, found ${found.map(([id]) => id).join(', ') || 'none'}`)
  }
  return found[0][1]
}
