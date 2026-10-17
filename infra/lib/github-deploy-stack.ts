import { CfnOutput, Duration, Stack, type StackProps } from 'aws-cdk-lib'
import * as iam from 'aws-cdk-lib/aws-iam'
import type { Construct } from 'constructs'
import { ENVIRONMENTS } from './environments.ts'

export type GitHubDeployStackProps = StackProps & {
  /** owner/name of the GitHub repository whose deploy workflow may assume the role. */
  repository: string
  /** An IAM OIDC provider for GitHub that already exists in the account; one is created otherwise. */
  existingProviderArn?: string
}

/**
 * The role that `.github/workflows/deploy.yml` assumes through GitHub's OIDC tokens, so no AWS keys are
 * stored in GitHub (D18). Only jobs running in this repository's `staging` or `prod` GitHub environment can
 * assume it. It can only assume the roles that `cdk bootstrap` created, which is what `cdk deploy` needs.
 * Deployed once per account, by hand: npx cdk deploy PhotoSpace-GitHubDeploy -c env=staging
 */
export class GitHubDeployStack extends Stack {
  constructor(scope: Construct, id: string, props: GitHubDeployStackProps) {
    super(scope, id, props)
    const issuer = 'token.actions.githubusercontent.com'
    const provider = props.existingProviderArn
      ? iam.OidcProviderNative.fromOidcProviderArn(this, 'GitHub', props.existingProviderArn)
      : new iam.OidcProviderNative(this, 'GitHub', { url: `https://${issuer}`, clientIds: ['sts.amazonaws.com'] })

    const role = new iam.Role(this, 'DeployRole', {
      roleName: 'photo-space-github-deploy',
      description: `GitHub Actions deployments of ${props.repository}`,
      maxSessionDuration: Duration.hours(1),
      assumedBy: new iam.OpenIdConnectPrincipal(provider, {
        StringEquals: { [`${issuer}:aud`]: 'sts.amazonaws.com' },
        'ForAnyValue:StringEquals': {
          [`${issuer}:sub`]: ENVIRONMENTS.map((environment) => `repo:${props.repository}:environment:${environment}`),
        },
      }),
    })
    role.addToPolicy(
      new iam.PolicyStatement({
        actions: ['sts:AssumeRole'],
        resources: [`arn:${this.partition}:iam::${this.account}:role/cdk-hnb659fds-*`],
      }),
    )
    new CfnOutput(this, 'DeployRoleArn', { value: role.roleArn, description: 'GitHub variable AWS_DEPLOY_ROLE_ARN' })
  }
}
