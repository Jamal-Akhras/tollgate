import { expect, test } from 'vitest';
import { activityBusyFixture } from '../../api/fixtures';
import type { CallView } from '../../api/types';
import {
  endpointName,
  filterCalls,
  filterQuery,
  guardsIn,
  readFilters,
  resultKind,
  tenantsIn,
  wouldRefuse,
} from './filters';

const calls = activityBusyFixture;
const none = { tenant: '', result: '', guard: '' };

function withOutcome(result: string): CallView {
  const base = calls.find((call) => call.outcome) as CallView;
  return { ...base, outcome: { ...(base.outcome as NonNullable<CallView['outcome']>), result } };
}

test('aCallThatWasNotAllowedIsRefused', () => {
  const refused = calls.find((call) => !call.allowed) as CallView;
  expect(resultKind(refused)).toBe('refused');
});

test('anAllowedCallWithoutAnOutcomeIsWaiting', () => {
  const call = { ...(calls.find((item) => item.allowed) as CallView), outcome: null };
  expect(resultKind(call)).toBe('waiting');
});

test('respondedIsSentDryRunIsDryRunAnythingElseIsFailed', () => {
  expect(resultKind(withOutcome('responded'))).toBe('sent');
  expect(resultKind(withOutcome('dry_run'))).toBe('dry run');
  expect(resultKind(withOutcome('upstream_error'))).toBe('failed');
});

test('filtersByTenant', () => {
  const rows = filterCalls(calls, { ...none, tenant: 'support-bot' });
  expect(rows.length).toBeGreaterThan(0);
  expect(rows.every((call) => call.tenant === 'support-bot')).toBe(true);
  expect(rows.length).toBeLessThan(calls.length);
});

test('filtersByResultKind', () => {
  const rows = filterCalls(calls, { ...none, result: 'refused' });
  expect(rows.length).toBeGreaterThan(0);
  expect(rows.every((call) => !call.allowed)).toBe(true);
});

test('aGuardMatchesWhenItsStepRefusedOrWouldHaveRefused', () => {
  const refusedByDataClass = filterCalls(calls, { ...none, guard: 'data-class' });
  expect(refusedByDataClass.every((call) => wouldRefuse(call, 'data-class'))).toBe(true);
  expect(refusedByDataClass.some((call) => !call.allowed)).toBe(true);
  const monitored = filterCalls(calls, { ...none, guard: 'tool-names' });
  expect(monitored.some((call) => call.allowed)).toBe(true);
  expect(
    monitored.every((call) =>
      call.steps.some((step) => step.guard === 'tool-names' && !step.allowed),
    ),
  ).toBe(true);
});

test('aGuardThatOnlyAllowedDoesNotMatch', () => {
  const allowed = calls.find(
    (call) => call.steps.length > 0 && call.steps.every((step) => step.allowed),
  );
  expect(allowed).toBeTruthy();
  expect(filterCalls([allowed as CallView], { ...none, guard: 'data-class' })).toEqual([]);
});

test('filtersCombine', () => {
  const rows = filterCalls(calls, {
    tenant: 'support-bot',
    result: 'refused',
    guard: 'data-class',
  });
  expect(rows.every((call) => call.tenant === 'support-bot' && !call.allowed)).toBe(true);
});

test('listsTheTenantsAndGuardsInTheCalls', () => {
  expect(tenantsIn(calls)).toEqual([...new Set(calls.map((call) => call.tenant))].sort());
  expect(guardsIn(calls)).toContain('data-class');
});

test('filtersRoundTripThroughTheQuery', () => {
  const filters = { tenant: 'research', result: '', guard: 'data-class' };
  expect(filterQuery(filters)).toEqual({ tenant: 'research', guard: 'data-class' });
  expect(readFilters(filterQuery(filters))).toEqual(filters);
  expect(readFilters({})).toEqual(none);
});

test('endpointsAreNamedByWireFormat', () => {
  expect(endpointName('openai')).toBe('OpenAI');
  expect(endpointName('anthropic')).toBe('Anthropic');
  expect(endpointName('other')).toBe('other');
});
