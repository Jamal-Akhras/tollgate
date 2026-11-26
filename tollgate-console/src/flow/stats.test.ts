import { expect, test } from 'vitest';
import { liveBusyFixture } from '../api/fixtures';
import type { Bucket, LiveSnapshot } from '../api/types';
import { statsFor } from './stats';

const now = Date.parse('2026-11-16T10:00:00Z') / 1000;

function bucket(secondsAgo: number, extra: Partial<Bucket>): Bucket {
  return {
    start: now - secondsAgo,
    sent: 0,
    refused: 0,
    failed: 0,
    clientClosed: 0,
    clientErrors: 0,
    serverErrors: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    p50Millis: null,
    p95Millis: null,
    ...extra,
  };
}

function snapshot(buckets: Bucket[], p95Millis: number | null = 900): LiveSnapshot {
  return { ...liveBusyFixture, now: '2026-11-16T10:00:00Z', buckets, p95Millis };
}

test('callsPerMinuteCountsTheLastMinuteAndTheSeriesHasFifteenMinutes', () => {
  const stats = statsFor(
    snapshot([
      bucket(5, { sent: 2, refused: 1 }),
      bucket(55, { sent: 3, failed: 1 }),
      bucket(70, { sent: 4 }),
      bucket(899, { sent: 7 }),
    ]),
  );
  expect(stats.callsPerMinute).toBe(7);
  expect(stats.callSeries).toHaveLength(15);
  expect(stats.callSeries[14]).toBe(7);
  expect(stats.callSeries[13]).toBe(4);
  expect(stats.callSeries[0]).toBe(7);
  expect(stats.callSeries[7]).toBe(0);
});

test('aBucketOlderThanFifteenMinutesIsLeftOut', () => {
  const stats = statsFor(snapshot([bucket(900, { sent: 50, refused: 9, cost: { USD: '5' } })]));
  expect(stats.callSeries.every((n) => n === 0)).toBe(true);
  expect(stats.refused).toBe(0);
  expect(stats.spend).toEqual({});
});

test('refusedAndSpendAreTotalsOverTheWindow', () => {
  const stats = statsFor(
    snapshot([
      bucket(5, { refused: 2, cost: { USD: '0.25' } }),
      bucket(200, { refused: 1, cost: { USD: '0.5', EUR: '1' } }),
    ]),
  );
  expect(stats.refused).toBe(3);
  expect(stats.spend).toEqual({ EUR: '1', USD: '0.75' });
  expect(stats.spendSeries[14]).toBeCloseTo(0.25);
  expect(stats.spendSeries[11]).toBeCloseTo(1.5);
});

test('theSlowestFivePercentComesFromTheSnapshotAndTheSeriesKeepsTheWorstOfEachMinute', () => {
  const stats = statsFor(
    snapshot([bucket(5, { p95Millis: 300 }), bucket(20, { p95Millis: 800 })], 1200),
  );
  expect(stats.p95Millis).toBe(1200);
  expect(stats.latencySeries[14]).toBe(800);
  expect(statsFor(snapshot([], null)).p95Millis).toBeNull();
});

test('theBusyFixtureGivesFifteenPointsForEveryTile', () => {
  const stats = statsFor(liveBusyFixture);
  for (const series of [
    stats.callSeries,
    stats.refusedSeries,
    stats.spendSeries,
    stats.latencySeries,
  ]) {
    expect(series).toHaveLength(15);
  }
  expect(stats.refused).toBeGreaterThan(0);
});
