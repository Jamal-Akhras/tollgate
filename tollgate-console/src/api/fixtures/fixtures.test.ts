import { expect, test } from 'vitest';
import * as fixtures from './index';

test('everyFixtureIsLoaded', () => {
  expect(Object.keys(fixtures)).toHaveLength(23);
});

test('theBusySetHasThreeTenantsAMonitoredGuardABudgetAFallbackAndRefusals', () => {
  const tenants = fixtures.configBusyFixture.tenants;
  expect(tenants).toHaveLength(3);
  expect(tenants.some((tenant) => tenant.budget !== null)).toBe(true);
  const guards = fixtures.configBusyFixture.policies.flatMap((policy) => policy.guards);
  expect(guards.some((guard) => guard.type === 'tool-names' && guard.mode === 'monitor')).toBe(true);
  expect(fixtures.activityBusyFixture.some((call) => call.fallback)).toBe(true);
  expect(fixtures.activityBusyFixture.some((call) => !call.allowed)).toBe(true);
  expect(fixtures.liveBusyFixture.flows.some((flow) => Object.keys(flow.refused).length > 0)).toBe(
    true,
  );
});

test('theBaseSetIsWhatInitWrites', () => {
  const config = fixtures.configFixture;
  expect(config.tenants.map((tenant) => tenant.name)).toEqual(['my-app']);
  expect(config.models.map((model) => model.name)).toEqual(['qwen3']);
  expect(config.providers.map((provider) => provider.name)).toEqual(['ollama']);
  expect(config.policies.map((policy) => policy.name)).toEqual(['default']);
});

test('liveBucketsCoverFifteenMinutesInFiveSecondSteps', () => {
  const { buckets, bucketSeconds } = fixtures.liveFixture;
  expect(buckets).toHaveLength(180);
  expect(buckets.every((bucket) => bucket.start % bucketSeconds === 0)).toBe(true);
});
