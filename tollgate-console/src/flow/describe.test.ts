import { expect, test } from 'vitest';
import {
  budgetsBusyFixture,
  configBusyFixture,
  configFixture,
  liveBusyFixture,
  liveFixture,
} from '../api/fixtures';
import type { ConfigView } from '../api/types';
import {
  blockedProviders,
  checksFor,
  opensFor,
  previewProviders,
  providersFor,
  sumDecimals,
  tenantCalls,
  tenantCaption,
} from './describe';

function summary(config: ConfigView, tenant: string, busy: boolean) {
  const checks = busy
    ? checksFor(config, tenant, budgetsBusyFixture, liveBusyFixture)
    : checksFor(config, tenant, [], liveFixture);
  return checks.map((check) => [check.label, check.detail, check.state]);
}

test('theBusyTenantWithABudgetListsEveryCheckInOrder', () => {
  expect(summary(configBusyFixture, 'support-bot', true)).toEqual([
    ['Request review', 'built in', 'ok'],
    ['data-class', 'internal → us, local', 'ok'],
    ['tool-names', 'monitor', 'monitor'],
    ['Personal data', '1 redacted', 'ok'],
    ['Budget', '$41.18 / $150', 'ok'],
    ['Rate limits', '600/min', 'ok'],
    ['Signed audit trail', 'batch 5', 'ok'],
  ]);
});

test('aTenantWithRedactionAndRefusalCountsBothDetectors', () => {
  expect(summary(configBusyFixture, 'research', true)).toEqual([
    ['Request review', 'built in', 'ok'],
    ['data-class', 'restricted → local', 'ok'],
    ['request-size', '64 KB', 'ok'],
    ['Personal data', '1 redacted, 1 refused', 'ok'],
    ['Rate limits', '30/min', 'ok'],
    ['Signed audit trail', 'batch 5', 'ok'],
  ]);
});

test('aMonitoredGuardIsMonitorAndCountsUnderItsType', () => {
  const checks = checksFor(configBusyFixture, 'support-bot', budgetsBusyFixture, liveBusyFixture);
  const monitored = checks.find((check) => check.label === 'tool-names');
  expect(monitored).toMatchObject({ state: 'monitor', detail: 'monitor', guard: 'tool-names' });
  expect(checks[0]?.guard).toBe('request-features');
  expect(checks.find((check) => check.id === 'personal-data')?.guard).toBe('personal-data');
});

test('aPolicyWithNoRedactionOrGuardsHasOnlyTheBuiltInChecks', () => {
  expect(summary(configFixture, 'my-app', false)).toEqual([
    ['Request review', 'built in', 'ok'],
    ['Signed audit trail', 'batch 1', 'ok'],
  ]);
});

test('aTenantWhosePolicyIsNotThereHasNoGuards', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) => ({ ...tenant, policy: 'gone' })),
  };
  expect(summary(config, 'support-bot', true).map((row) => row[0])).toEqual([
    'Request review',
    'Budget',
    'Rate limits',
    'Signed audit trail',
  ]);
});

test('aBudgetBeforeTheFirstPollShowsItsLimit', () => {
  const checks = checksFor(configBusyFixture, 'support-bot', [], null);
  expect(checks.find((check) => check.id === 'budget')?.detail).toBe('$150 monthly');
});

test('theAuditTrailIsOffWhenTheConfigSaysSo', () => {
  const config = { ...configFixture, audit: { mode: 'off', publicKey: null } };
  const checks = checksFor(config, 'my-app', [], null);
  expect(checks.at(-1)).toMatchObject({ detail: 'off', state: 'off' });
});

test('anUnknownTenantHasNoChecksOrProviders', () => {
  expect(checksFor(configFixture, 'nobody', [], null)).toEqual([]);
  expect(providersFor(configFixture, 'nobody', null)).toEqual([]);
});

test('providersAreTheOnesTheTenantsModelsServeWithCountsAndSpend', () => {
  const providers = providersFor(configBusyFixture, 'support-bot', liveBusyFixture);
  expect(providers.map((provider) => provider.name)).toEqual(['anthropic', 'ollama', 'openai']);
  expect(providers.map((provider) => provider.calls)).toEqual([64, 11, 416]);
  expect(providers.every((provider) => provider.reachable && !provider.fallbackOnly)).toBe(true);
  expect(providers[0]?.caption).toBe('us · 64 calls · $0.53');
  expect(providers[1]?.caption).toBe('local · 11 calls · free · 1 in flight');
  expect(providers[2]?.caption).toBe('us · 416 calls · $0.094 · 3 in flight');
});

test('aTenantOnlyListsItsOwnProviders', () => {
  const providers = providersFor(configBusyFixture, 'research', liveBusyFixture);
  expect(providers.map((provider) => provider.name)).toEqual(['ollama']);
  expect(providers[0]?.calls).toBe(38);
});

test('aProviderOutsideTheClassesRegionsIsNotReachableAndSaysWhy', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) =>
      tenant.name === 'research' ? { ...tenant, models: ['claude-sonnet'] } : tenant,
    ),
  };
  const providers = providersFor(config, 'research', liveBusyFixture);
  const anthropic = providers.find((provider) => provider.name === 'anthropic');
  expect(anthropic).toMatchObject({
    reachable: false,
    fallbackOnly: false,
    caption: 'us · not for restricted',
  });
  expect(providers.find((provider) => provider.name === 'ollama')?.reachable).toBe(true);
});

test('aProviderReachedOnlyThroughFallbackIsMarkedAndFollowsTheChain', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) =>
      tenant.name === 'my-app' ? { ...tenant, models: ['claude-sonnet'] } : tenant,
    ),
  };
  const providers = providersFor(config, 'my-app', liveBusyFixture);
  expect(providers.map((provider) => [provider.name, provider.fallbackOnly])).toEqual([
    ['anthropic', false],
    ['ollama', true],
    ['openai', true],
  ]);
  expect(providers[1]?.caption).toBe('local · 5 calls · free · fallback · 1 in flight');
});

test('aMonitoredDataClassGuardReachesEveryProvider', () => {
  const config = {
    ...configBusyFixture,
    policies: configBusyFixture.policies.map((policy) => ({
      ...policy,
      guards: policy.guards.map((guard) => ({ ...guard, mode: 'monitor' })),
    })),
    tenants: configBusyFixture.tenants.map((tenant) =>
      tenant.name === 'research' ? { ...tenant, models: ['claude-sonnet'] } : tenant,
    ),
  };
  const providers = providersFor(config, 'research', null);
  expect(providers.every((provider) => provider.reachable)).toBe(true);
  expect(providers[0]?.caption).toBe('us · 0 calls');
});

test('aTenantWithNoDataClassCannotReachAnythingWhileTheGuardEnforces', () => {
  const config = {
    ...configBusyFixture,
    tenants: configBusyFixture.tenants.map((tenant) =>
      tenant.name === 'my-app' ? { ...tenant, dataClass: null } : tenant,
    ),
  };
  expect(providersFor(config, 'my-app', null)[0]?.caption).toBe('local · needs a data class');
});

test('moneyIsSummedWithoutRounding', () => {
  expect(sumDecimals(['0.1', '0.0001'])).toBe('0.1001');
  expect(sumDecimals(['0.1', '0.2'])).toBe('0.3');
  expect(sumDecimals(['1.50', '2.5'])).toBe('4');
});

test('tenantCallsCountSentFailedAndRefused', () => {
  expect(tenantCalls(liveBusyFixture, 'support-bot')).toBe(503);
  expect(tenantCalls(liveBusyFixture, 'research')).toBe(40);
  expect(tenantCalls(null, 'research')).toBe(0);
});

test('aTenantCaptionCountsKeysAndNamesTheClass', () => {
  const [mine, research, bot] = configBusyFixture.tenants;
  expect(tenantCaption(configBusyFixture, bot!)).toBe('2 keys, internal');
  expect(tenantCaption(configBusyFixture, research!)).toBe('1 key, restricted');
  expect(tenantCaption(configFixture, { ...mine!, dataClass: null })).toBe('1 key, no data class');
});

test('aTenantsClassBlocksTheProvidersInRegionsItMayNotReach', () => {
  expect(blockedProviders(configBusyFixture, 'research').map((p) => p.name)).toEqual([
    'anthropic',
    'openai',
  ]);
  expect(blockedProviders(configBusyFixture, 'support-bot')).toEqual([]);
  expect(blockedProviders(configBusyFixture, 'nobody')).toEqual([]);
});

test('thePreviewListsEveryProviderInTheConfigAsReachableDirectly', () => {
  const nodes = previewProviders(configBusyFixture, 'research', liveBusyFixture);
  expect(nodes.map((node) => node.name)).toEqual(['anthropic', 'ollama', 'openai']);
  expect(nodes.every((node) => node.reachable)).toBe(true);
  expect(nodes[0]!.caption).toBe('us · reachable directly');
  expect(nodes[1]!.caption).toContain('local · ');
});

test('aClassWithAnOpenRegionNamesIt', () => {
  expect(opensFor(configBusyFixture, 'strict')).toEqual([
    { dataClass: 'restricted', regions: ['us'] },
  ]);
});

test('aClassWhoseProvidersAreAllInAllowedRegionsOpensNothing', () => {
  expect(opensFor(configBusyFixture, 'default')).toEqual([]);
});

test('aPolicyNoTenantUsesOrWithoutADataClassGuardOpensNothing', () => {
  const config = {
    ...configBusyFixture,
    policies: [...configBusyFixture.policies, { ...configBusyFixture.policies[0]!, name: 'spare' }],
  };
  expect(opensFor(config, 'spare')).toEqual([]);
  expect(opensFor(configFixture, 'default')).toEqual([]);
  expect(opensFor(config, 'gone')).toEqual([]);
});