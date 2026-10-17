import { Stack, type StackProps } from 'aws-cdk-lib'
import * as wafv2 from 'aws-cdk-lib/aws-wafv2'
import type { Construct } from 'constructs'
import type { EnvironmentName } from './environments.ts'

export type EdgeStackProps = StackProps & { environmentName: EnvironmentName }

/** Requests per client IP address in any 5-minute window, above which WAF blocks that address for a while. */
export const RATE_LIMITS = {
  /** Everything: the app shell, its assets, and the API. Uploads go straight to S3 and are not counted. */
  all: 3000,
  /** Sign-in endpoints. */
  auth: 100,
  /** The gallery visitor API (D14). Share tokens can't be guessed, but nobody should get to try at scale. */
  publicGallery: 600,
}

/**
 * The CloudFront web application firewall (D18). CloudFront only accepts a web ACL from us-east-1, so this
 * stack lives there whatever the application's region is. It rate-limits per client IP address and applies
 * AWS's managed rules for common exploits and known bad inputs.
 */
export class EdgeStack extends Stack {
  readonly webAcl: wafv2.CfnWebACL

  constructor(scope: Construct, id: string, props: EdgeStackProps) {
    super(scope, id, props)
    const name = `photo-space-${props.environmentName}`
    const visibility = (metricName: string) => ({
      cloudWatchMetricsEnabled: true,
      metricName,
      sampledRequestsEnabled: true,
    })
    const rateRule = (ruleName: string, priority: number, limit: number, pathPrefix?: string) => ({
      name: ruleName,
      priority,
      action: { block: {} },
      statement: {
        rateBasedStatement: {
          limit,
          aggregateKeyType: 'IP',
          evaluationWindowSec: 300,
          scopeDownStatement: pathPrefix
            ? {
                byteMatchStatement: {
                  fieldToMatch: { uriPath: {} },
                  positionalConstraint: 'STARTS_WITH',
                  searchString: pathPrefix,
                  textTransformations: [{ priority: 0, type: 'NONE' }],
                },
              }
            : undefined,
        },
      },
      visibilityConfig: visibility(ruleName),
    })
    const managedRule = (ruleName: string, priority: number, overrides: string[] = []) => ({
      name: ruleName,
      priority,
      overrideAction: { none: {} },
      statement: {
        managedRuleGroupStatement: {
          vendorName: 'AWS',
          name: ruleName,
          ruleActionOverrides: overrides.map((rule) => ({ name: rule, actionToUse: { count: {} } })),
        },
      },
      visibilityConfig: visibility(ruleName),
    })

    this.webAcl = new wafv2.CfnWebACL(this, 'WebAcl', {
      name: `${name}-cdn`,
      scope: 'CLOUDFRONT',
      defaultAction: { allow: {} },
      visibilityConfig: visibility(`${name}-cdn`),
      rules: [
        rateRule('rate-limit-auth', 0, RATE_LIMITS.auth, '/api/auth/'),
        rateRule('rate-limit-public-gallery', 1, RATE_LIMITS.publicGallery, '/api/public/'),
        rateRule('rate-limit-all', 2, RATE_LIMITS.all),
        // The 8 KB body limit only counts: photographers' notes and brand profiles are JSON bodies that may be
        // longer, and the API validates sizes itself.
        managedRule('AWSManagedRulesCommonRuleSet', 3, ['SizeRestrictions_BODY']),
        managedRule('AWSManagedRulesKnownBadInputsRuleSet', 4),
      ],
    })
  }
}
