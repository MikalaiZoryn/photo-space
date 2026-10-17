import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { App } from 'aws-cdk-lib'
import { Template } from 'aws-cdk-lib/assertions'
import { GitHubDeployStack } from '../lib/github-deploy-stack.ts'
import { ACCOUNT, resource } from './support.ts'

describe('GitHubDeployStack', () => {
  const template = (existingProviderArn?: string) =>
    Template.fromStack(
      new GitHubDeployStack(new App(), 'GitHub', { repository: 'owner/repo', existingProviderArn, env: { account: ACCOUNT, region: 'us-west-2' } }),
    )

  it('trusts only the deploy environments of one repository', () => {
    const role = resource(template(), 'AWS::IAM::Role', 'DeployRole').Properties
    const condition = role.AssumeRolePolicyDocument.Statement[0].Condition
    assert.deepEqual(condition.StringEquals, { 'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com' })
    assert.deepEqual(condition['ForAnyValue:StringEquals']['token.actions.githubusercontent.com:sub'], [
      'repo:owner/repo:environment:staging',
      'repo:owner/repo:environment:prod',
    ])
  })

  it('can only assume the CDK bootstrap roles', () => {
    const statements = resource(template(), 'AWS::IAM::Policy', 'DeployRoleDefaultPolicy').Properties.PolicyDocument.Statement
    assert.deepEqual(statements.map((s: Record<string, unknown>) => s.Action), ['sts:AssumeRole'])
    assert.match(JSON.stringify(statements[0].Resource), /:role\/cdk-hnb659fds-\*"/)
  })

  it('reuses an existing GitHub identity provider', () => {
    assert.equal(Object.keys(template('arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com').findResources('AWS::IAM::OIDCProvider')).length, 0)
    assert.equal(Object.keys(template().findResources('AWS::IAM::OIDCProvider')).length, 1)
  })
})
