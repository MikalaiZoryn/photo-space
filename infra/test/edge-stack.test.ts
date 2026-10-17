import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { RATE_LIMITS } from '../lib/edge-stack.ts'
import { environmentConfig } from '../lib/environments.ts'
import { resource, synthesize } from './support.ts'

describe('EdgeStack', () => {
  const acl = () => resource(synthesize().edge, 'AWS::WAFv2::WebACL', 'WebAcl').Properties

  it('is a CloudFront firewall that allows by default', () => {
    assert.equal(acl().Scope, 'CLOUDFRONT')
    assert.deepEqual(acl().DefaultAction, { Allow: {} })
  })

  it('rate-limits sign-in, the gallery visitor API, and everything per client IP', () => {
    const rules = acl().Rules as Record<string, any>[]
    const limit = (name: string) => rules.find((rule) => rule.Name === name)!.Statement.RateBasedStatement
    assert.equal(limit('rate-limit-auth').Limit, RATE_LIMITS.auth)
    assert.equal(limit('rate-limit-auth').ScopeDownStatement.ByteMatchStatement.SearchString, '/api/auth/')
    assert.equal(limit('rate-limit-public-gallery').ScopeDownStatement.ByteMatchStatement.SearchString, '/api/public/')
    assert.equal(limit('rate-limit-all').Limit, RATE_LIMITS.all)
    for (const name of ['rate-limit-auth', 'rate-limit-public-gallery', 'rate-limit-all']) {
      assert.equal(limit(name).AggregateKeyType, 'IP')
      assert.deepEqual(rules.find((rule) => rule.Name === name)!.Action, { Block: {} })
    }
  })

  it('applies the managed common and known-bad-input rules', () => {
    const names = (acl().Rules as Record<string, any>[]).map((rule) => rule.Statement.ManagedRuleGroupStatement?.Name)
    assert.ok(names.includes('AWSManagedRulesCommonRuleSet'))
    assert.ok(names.includes('AWSManagedRulesKnownBadInputsRuleSet'))
  })
})

describe('environmentConfig', () => {
  it('knows staging and prod', () => {
    assert.equal(environmentConfig('staging').region, 'us-west-2')
    assert.equal(environmentConfig('prod').database.multiAz, true)
  })

  it('rejects anything else', () => {
    for (const environment of [undefined, '', 'dev', 'production']) {
      assert.throws(() => environmentConfig(environment), /env=/, String(environment))
    }
  })
})
