import { expect, test } from 'vitest';
import { liveBusyFixture } from '../../api/fixtures';
import type { Bucket, LiveSnapshot } from '../../api/types';
import { chartsFor } from './series';

function bucket(start: number, rest: Partial<Bucket> = {}): Bucket {
  return {
    start,
    sent: 0,
    refused: 0,
    failed: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    p50Millis: null,
    p95Millis: null,
    ...rest,
  };
}

function snapshot(buckets: Bucket[]): LiveSnapshot {
  return { ...liveBusyFixture, buckets };
}

test('turnsFiveSecondTotalsIntoPerSecondRates', () => {
  const charts = chartsFor(
    snapshot([
      bucket(1000, { sent: 10, refused: 5, failed: 0, inputTokens: 100, outputTokens: 50 }),
      bucket(1005, { sent: 0, refused: 0, failed: 5, inputTokens: 0, outputTokens: 25 }),
    ]),
  );
  expect(charts.calls.x).toEqual([1000, 1005]);
  expect(charts.calls.lines.map((line) => [line.name, line.values])).toEqual([
    ['Sent', [2, 0]],
    ['Refused', [1, 0]],
    ['Failed', [0, 1]],
  ]);
  expect(charts.tokens.lines.map((line) => [line.name, line.values])).toEqual([
    ['Input', [20, 0]],
    ['Output', [10, 5]],
  ]);
});

test('sumsSpendByMinuteAndByCurrencyWithoutRounding', () => {
  const charts = chartsFor(
    snapshot([
      bucket(1200, { cost: { USD: '0.001' } }),
      bucket(1205, { cost: { USD: '0.0020', EUR: '0.5' } }),
      bucket(1260, { cost: { USD: '0.1' } }),
      bucket(1265),
    ]),
  );
  expect(charts.spend.x).toEqual([1200, 1260]);
  expect(charts.spend.lines.map((line) => [line.name, line.values])).toEqual([
    ['EUR', [0.5, 0]],
    ['USD', [0.003, 0.1]],
  ]);
});

test('keepsGapsWhereAnswersHaveNoPercentiles', () => {
  const charts = chartsFor(
    snapshot([
      bucket(1000, { p50Millis: 400, p95Millis: 900 }),
      bucket(1005),
      bucket(1010, { p50Millis: 420, p95Millis: 950 }),
    ]),
  );
  expect(charts.latency.lines.map((line) => [line.name, line.values])).toEqual([
    ['p50', [400, null, 420]],
    ['p95', [900, null, 950]],
  ]);
});

test('givesEmptyChartsForAnEmptyWindow', () => {
  const charts = chartsFor(snapshot([]));
  for (const chart of [charts.calls, charts.tokens, charts.spend, charts.latency]) {
    expect(chart.x).toEqual([]);
    for (const line of chart.lines) {
      expect(line.values).toEqual([]);
    }
  }
  expect(charts.spend.lines).toEqual([]);
});
