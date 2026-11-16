import { expect, test } from 'vitest';
import { configBusyFixture, liveBusyFixture } from '../api/fixtures';
import type { Flow, GuardCount } from '../api/types';
import { checksFor } from './describe';
import { layout } from './layout';
import { curve, linesFor, type LineInput } from './paths';

const now = Date.parse('2026-11-16T10:00:00Z');
const checks = checksFor(configBusyFixture, 'support-bot', [], liveBusyFixture);
const picture = layout(1200, { tenants: 3, checks: checks.length, providers: 3 })!;

function flow(extra: Partial<Flow>): Flow {
  return {
    tenant: 'support-bot',
    model: 'gpt-fast',
    provider: 'openai',
    sent: 1,
    refused: {},
    failed: 0,
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

function input(flows: Flow[], guards: GuardCount[] = []): LineInput {
  return { flows, providers: ['anthropic', 'ollama', 'openai'], checks, guards };
}

function seconds(n: number): string {
  return new Date(now - n * 1000).toISOString();
}

test('aCurveHasBothControlPointsAtTheHorizontalMidpoint', () => {
  expect(curve({ x: 10, y: 20 }, { x: 110, y: 80 })).toBe('M 10 20 C 60 20, 60 80, 110 80');
});

test('aLineIsMovingWhenItsFlowSentWithinTenSeconds', () => {
  const lines = linesFor(picture, input([flow({ lastSent: seconds(10) })]), now);
  const openai = lines.find((line) => line.id === 'provider-openai');
  expect(openai).toMatchObject({ kind: 'sent', moving: true });
  expect(lines.find((line) => line.id === 'tenant')).toMatchObject({ kind: 'sent', moving: true });
});

test('aLineStopsMovingAfterTenSeconds', () => {
  const lines = linesFor(picture, input([flow({ lastSent: seconds(11) })]), now);
  expect(lines.find((line) => line.id === 'provider-openai')).toMatchObject({
    kind: 'idle',
    moving: false,
  });
  expect(lines.find((line) => line.id === 'tenant')).toMatchObject({ kind: 'idle', moving: false });
});

test('aFallbackWithinTenSecondsIsAFallbackLine', () => {
  const lines = linesFor(
    picture,
    input([flow({ provider: 'ollama', lastSent: seconds(2), lastFallback: seconds(2) })]),
    now,
  );
  expect(lines.find((line) => line.id === 'provider-ollama')).toMatchObject({
    kind: 'fallback',
    moving: true,
  });
});

test('everyProviderGetsALineEvenWithNoTraffic', () => {
  const lines = linesFor(picture, input([]), now);
  expect(lines.map((line) => line.id)).toEqual([
    'tenant',
    'provider-anthropic',
    'provider-ollama',
    'provider-openai',
  ]);
  expect(lines.every((line) => line.kind === 'idle' && !line.moving)).toBe(true);
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
  expect(refused.moving).toBe(true);
  expect(refused.pill).toBe(1);
  expect(refused.from.x).toBe(picture.pills[1]!.x + picture.pills[1]!.w);
  expect(refused.end).not.toBeNull();
  expect(refused.d.startsWith(`M ${refused.from.x} ${refused.from.y}`)).toBe(true);
});

test('aRefusalFromTheWindowButNotJustNowStaysAsAStaticCross', () => {
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
  expect(refused.moving).toBe(false);
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

test('theTenantLineRunsFromTheSelectedCardToThePolicyBox', () => {
  const line = linesFor(picture, input([]), now)[0]!;
  const card = picture.tenants[0]!;
  expect(line.from).toEqual({ x: card.x + card.w + 8, y: card.y + card.h / 2 });
  expect(line.d.endsWith(`${picture.policy.x} ${card.y + card.h / 2}`)).toBe(true);
});

test('thePreviewDrawsOneGreyLineFromTheTenantToEveryProviderAndNothingElse', () => {
  const lines = linesFor(
    picture,
    {
      ...input([
        flow({ lastSent: seconds(1), refused: { data_class: 1 }, lastRefused: seconds(1) }),
      ]),
      preview: true,
    },
    now,
  );
  expect(lines.map((line) => line.id)).toEqual([
    'direct-anthropic',
    'direct-ollama',
    'direct-openai',
  ]);
  expect(lines.every((line) => line.kind === 'idle' && !line.moving && line.end === null)).toBe(true);
  const card = picture.tenants[0]!;
  expect(lines[0]!.from).toEqual({ x: card.x + card.w + 8, y: card.y + card.h / 2 });
  expect(lines[1]!.d.endsWith(`${picture.providers[1]!.x - 8} ${picture.providers[1]!.y + 48}`)).toBe(true);
});
