import { expect, test } from 'vitest';
import { configBusyFixture, liveBusyFixture } from '../api/fixtures';
import type { Flow, GuardCount } from '../api/types';
import { checksFor } from './describe';
import { layout } from './layout';
import { curve, linesFor, type LineInput, type Origin } from './paths';

const now = Date.parse('2026-11-16T10:00:00Z');
const checks = checksFor(configBusyFixture, 'support-bot', [], liveBusyFixture);
const picture = layout(1200, { tenants: 3, checks: checks.length, providers: 3 })!;
const names = ['support-bot', 'research', 'batch'];

function flow(extra: Partial<Flow>): Flow {
  return {
    tenant: 'support-bot',
    model: 'gpt-fast',
    provider: 'openai',
    sent: 1,
    refused: {},
    failed: 0,
    clientClosed: 0,
    clientErrors: 0,
    serverErrors: 0,
    fallbacks: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    redacted: 0,
    lastSent: null,
    lastRefused: null,
    lastFallback: null,
    ...extra,
  };
}

function origins(selected = 'support-bot'): Origin[] {
  return names.map((name, index) => ({
    name,
    rect: picture.tenants[index]!,
    selected: name === selected,
  }));
}

function input(flows: Flow[], guards: GuardCount[] = []): LineInput {
  return {
    tenants: origins(),
    selected: 'support-bot',
    flows,
    providers: ['anthropic', 'ollama', 'openai'],
    checks,
    guards,
  };
}

function seconds(n: number): string {
  return new Date(now - n * 1000).toISOString();
}

test('aCurveHasBothControlPointsAtTheHorizontalMidpoint', () => {
  expect(curve({ x: 10, y: 20 }, { x: 110, y: 80 })).toBe('M 10 20 C 60 20, 60 80, 110 80');
});

test('aLineFlowsFastWhenItsFlowSentWithinTenSeconds', () => {
  const lines = linesFor(picture, input([flow({ lastSent: seconds(10) })]), now);
  const openai = lines.find((line) => line.id === 'provider-openai');
  expect(openai).toMatchObject({ kind: 'sent', moving: true, fast: true });
  expect(lines.find((line) => line.id === 'tenant-support-bot')).toMatchObject({
    kind: 'sent',
    moving: true,
    fast: true,
  });
});

test('aLineKeepsFlowingSlowlyForTheRestOfTheWindow', () => {
  const lines = linesFor(picture, input([flow({ lastSent: seconds(11) })]), now);
  expect(lines.find((line) => line.id === 'provider-openai')).toMatchObject({
    kind: 'sent',
    moving: true,
    fast: false,
  });
  expect(lines.find((line) => line.id === 'tenant-support-bot')).toMatchObject({
    kind: 'sent',
    moving: true,
    fast: false,
  });
});

test('aProviderThatJustTookAFallbackGetsAFallbackLine', () => {
  const taken = flow({
    provider: 'ollama',
    sent: 6,
    fallbacks: 1,
    lastFallback: seconds(4),
    lastSent: seconds(2),
  });
  expect(
    linesFor(picture, input([taken]), now).find((line) => line.id === 'provider-ollama'),
  ).toMatchObject({ kind: 'fallback', moving: true, fast: true });
});

test('aProviderUsedOnlyAsAFallbackKeepsItsFallbackLineForTheWindow', () => {
  const only = flow({
    provider: 'ollama',
    sent: 2,
    fallbacks: 2,
    lastFallback: seconds(120),
    lastSent: seconds(118),
  });
  expect(
    linesFor(picture, input([only]), now).find((line) => line.id === 'provider-ollama'),
  ).toMatchObject({ kind: 'fallback', moving: true, fast: false });
});

test('anOldFallbackAmongDirectCallsIsASentLine', () => {
  const mostly = flow({
    provider: 'ollama',
    sent: 9,
    fallbacks: 1,
    lastFallback: seconds(60),
    lastSent: seconds(1),
  });
  expect(
    linesFor(picture, input([mostly]), now).find((line) => line.id === 'provider-ollama')?.kind,
  ).toBe('sent');
});

test('everyTenantAndProviderGetsALineEvenWithNoTraffic', () => {
  const lines = linesFor(picture, input([]), now);
  expect(lines.map((line) => line.id)).toEqual([
    'tenant-research',
    'tenant-batch',
    'tenant-support-bot',
    'provider-anthropic',
    'provider-ollama',
    'provider-openai',
  ]);
  expect(lines.every((line) => line.kind === 'idle' && !line.moving)).toBe(true);
  expect(lines.filter((line) => line.faint).map((line) => line.id)).toEqual([
    'tenant-research',
    'tenant-batch',
  ]);
});

test('anotherTenantsCallsColourItsOwnFaintLineAndNothingElse', () => {
  const lines = linesFor(picture, input([flow({ tenant: 'research', lastSent: seconds(1) })]), now);
  expect(lines.find((line) => line.id === 'tenant-research')).toMatchObject({
    kind: 'sent',
    moving: true,
    faint: true,
  });
  expect(lines.find((line) => line.id === 'tenant-support-bot')?.kind).toBe('idle');
  expect(lines.find((line) => line.id === 'provider-openai')?.kind).toBe('idle');
  expect(lines.some((line) => line.kind === 'refused')).toBe(false);
});

test('aRefusalLeavesThePillOfTheGuardThatRefusedAndEndsInACross', () => {
  const guard: GuardCount = {
    tenant: 'support-bot',
    guard: 'data-class',
    allowed: 1,
    refused: 1,
    monitored: 0,
    lastRefused: seconds(3),
  };
  const lines = linesFor(
    picture,
    input([flow({ refused: { data_class: 1 }, lastRefused: seconds(3) })], [guard]),
    now,
  );
  const refused = lines.find((line) => line.kind === 'refused')!;
  expect(refused).toMatchObject({ id: 'refused-guard-0', moving: true, fast: true });
  expect(refused.pill).toBe(1);
  expect(refused.from.x).toBe(picture.pills[1]!.x + picture.pills[1]!.w);
  const card = picture.refused;
  expect(refused.d.startsWith(`M ${refused.from.x} ${refused.from.y}`)).toBe(true);
  expect(refused.d.endsWith(`${card.x - 8} ${card.y + card.h / 2}`)).toBe(true);
});

test('everyGuardThatRefusedGetsItsOwnLineToTheRefusedCard', () => {
  const count = (guard: string, lastRefused: string): GuardCount => ({
    tenant: 'support-bot',
    guard,
    allowed: 1,
    refused: 2,
    monitored: 0,
    lastRefused,
  });
  const lines = linesFor(
    picture,
    input(
      [flow({ refused: { policy_denied: 4, budget_exceeded: 1 }, lastRefused: seconds(2) })],
      [count('data-class', seconds(300)), count('tool-names', seconds(2))],
    ),
    now,
  ).filter((line) => line.kind === 'refused');
  expect(lines.map((line) => line.pill)).toEqual([
    1,
    2,
    checks.findIndex((check) => check.id === 'budget'),
  ]);
  expect(lines.map((line) => line.fast)).toEqual([false, true, true]);
  expect(new Set(lines.map((line) => line.d.split(', ').at(-1))).size).toBe(1);
});

test('aRefusalFromTheWindowButNotJustNowKeepsFlowingSlowly', () => {
  const guard: GuardCount = {
    tenant: 'support-bot',
    guard: 'tool-names',
    allowed: 1,
    refused: 0,
    monitored: 0,
    lastRefused: seconds(60),
  };
  const lines = linesFor(
    picture,
    input([flow({ refused: { tool_not_allowed: 3 }, lastRefused: seconds(60) })], [guard]),
    now,
  );
  const refused = lines.find((line) => line.kind === 'refused')!;
  expect(refused).toMatchObject({ moving: true, fast: false });
  expect(refused.pill).toBe(2);
});

test('aRefusalNoGuardCountsLeavesTheBudgetPill', () => {
  const lines = linesFor(
    picture,
    input([flow({ refused: { budget_exceeded: 1 }, lastRefused: seconds(1) })]),
    now,
  );
  expect(lines.find((line) => line.kind === 'refused')?.pill).toBe(
    checks.findIndex((check) => check.id === 'budget'),
  );
});

test('noRefusalsMeansNoRefusalLine', () => {
  const lines = linesFor(picture, input([flow({ lastSent: seconds(1) })]), now);
  expect(lines.some((line) => line.kind === 'refused')).toBe(false);
});

test('eachTenantLineRunsFromItsOwnCardToTheMiddleOfThePolicyBox', () => {
  const lines = linesFor(picture, { ...input([]), tenants: origins('research') }, now);
  const middle = picture.policy.y + picture.policy.h / 2;
  picture.tenants.forEach((card, index) => {
    const name = names[index];
    const line = lines.find((item) => item.id === `tenant-${name}`)!;
    expect(line.from).toEqual({ x: card.x + card.w + 8, y: card.y + card.h / 2 });
    expect(line.d.endsWith(`${picture.policy.x} ${middle}`)).toBe(true);
    expect(line.faint).toBe(name !== 'research');
  });
});

test('aSelectedTenantScrolledOutOfViewKeepsItsProviderAndRefusalLines', () => {
  const tenants = origins().filter((tenant) => !tenant.selected);
  const refused = flow({ lastSent: seconds(1), refused: { data_class: 1 }, lastRefused: seconds(1) });
  const lines = linesFor(picture, { ...input([refused]), tenants }, now);
  expect(lines.map((line) => line.id)).toEqual([
    'tenant-research',
    'tenant-batch',
    'provider-anthropic',
    'provider-ollama',
    'provider-openai',
    'refused-budget',
  ]);
  expect(lines.find((line) => line.id === 'provider-openai')?.kind).toBe('sent');
  expect(linesFor(picture, { ...input([refused]), tenants, preview: true }, now)).toEqual([]);
});

test('thePreviewDrawsOneGreyLineFromTheTenantToEveryProviderAndNothingElse', () => {
  const lines = linesFor(
    picture,
    {
      ...input([
        flow({ lastSent: seconds(1), refused: { data_class: 1 }, lastRefused: seconds(1) }),
      ]),
      tenants: origins('research'),
      preview: true,
    },
    now,
  );
  expect(lines.map((line) => line.id)).toEqual([
    'direct-anthropic',
    'direct-ollama',
    'direct-openai',
  ]);
  expect(lines.every((line) => line.kind === 'idle' && !line.moving)).toBe(true);
  const card = picture.tenants[1]!;
  expect(lines[0]!.from).toEqual({ x: card.x + card.w + 8, y: card.y + card.h / 2 });
  const second = picture.providers[1]!;
  expect(lines[1]!.d.endsWith(`${second.x - 8} ${second.y + 48}`)).toBe(true);
});
