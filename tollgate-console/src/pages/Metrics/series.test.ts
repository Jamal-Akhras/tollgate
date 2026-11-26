import { expect, test } from 'vitest';
import { liveBusyFixture } from '../../api/fixtures';
import { chartFor, settled, sparksFor } from './series';
import { bucket, snapshot } from './snapshots';

function named(chart: ReturnType<typeof chartFor>) {
  return chart.lines.map((line) => [line.name, line.values]);
}

test('leavesOutTheUnfinishedLastBucket', () => {
  const live = snapshot({
    buckets: [bucket(1000, { sent: 1 }), bucket(1060, { sent: 1 }), bucket(1120)],
  });
  expect(settled(live).map((item) => item.start)).toEqual([1000, 1060]);
  expect(chartFor(live, 'calls').x).toEqual([1000, 1060]);
  expect(sparksFor(live).calls).toEqual([1, 1]);
});

test('stacksAnsweredClientErrorRefusedAndFailedPerMinute', () => {
  const live = snapshot({
    bucketSeconds: 15,
    buckets: [
      bucket(1000, { sent: 10, clientErrors: 2, serverErrors: 1, refused: 3, failed: 1 }),
      bucket(1015),
    ],
  });
  const chart = chartFor(live, 'calls');
  expect(chart.unit).toBe('calls per minute');
  expect(chart.stacked).toBe(true);
  expect(chart.bars).toBe(true);
  expect(named(chart)).toEqual([
    ['Answered', [28]],
    ['Client errors', [8]],
    ['Refused', [12]],
    ['Failed', [8]],
  ]);
  expect(chart.lines.map((line) => line.token)).toEqual([
    '--ok',
    '--info',
    '--accent',
    '--danger',
  ]);
});

test('stacksInputAndOutputTokensPerMinute', () => {
  const live = snapshot({
    bucketSeconds: 60,
    buckets: [bucket(1000, { sent: 1, inputTokens: 100, outputTokens: 40 }), bucket(1060)],
  });
  const chart = chartFor(live, 'tokens');
  expect(chart.unit).toBe('tokens per minute');
  expect(named(chart)).toEqual([
    ['Input', [100]],
    ['Output', [40]],
  ]);
});

test('drawsOneSeriesPerCurrencyAndNamesTheUnitWhenThereIsOne', () => {
  const live = snapshot({
    bucketSeconds: 60,
    buckets: [
      bucket(1000, { sent: 1, cost: { USD: '0.25' } }),
      bucket(1060, { sent: 1, cost: { USD: '0.5', EUR: '1' } }),
      bucket(1120),
    ],
  });
  const both = chartFor(live, 'spend');
  expect(both.unit).toBe('per minute');
  expect(named(both)).toEqual([
    ['EUR', [0, 1]],
    ['USD', [0.25, 0.5]],
  ]);
  const single = snapshot({
    bucketSeconds: 60,
    buckets: [bucket(1000, { sent: 1, cost: { USD: '2' } }), bucket(1060)],
  });
  const one = chartFor(single, 'spend');
  expect(one.unit).toBe('USD per minute');
  expect(one.bars).toBe(true);
  expect(one.stacked).toBe(false);
});

test('drawsFlatSpendWhenNoCostWasRecorded', () => {
  const live = snapshot({ bucketSeconds: 60, buckets: [bucket(1000, { sent: 1 }), bucket(1060)] });
  expect(named(chartFor(live, 'spend'))).toEqual([['Spend', [0]]]);
});

test('drawsLatencyAsLinesWithGapsWhereThereAreNoPercentiles', () => {
  const live = snapshot({
    buckets: [
      bucket(1000, { sent: 1, p50Millis: 400, p95Millis: 900 }),
      bucket(1060),
      bucket(1120, { sent: 1, p50Millis: 420, p95Millis: 950 }),
      bucket(1180),
    ],
  });
  const chart = chartFor(live, 'latency');
  expect(chart.unit).toBe('ms');
  expect(chart.bars).toBe(false);
  expect(named(chart)).toEqual([
    ['p50', [400, null, 420]],
    ['p95', [900, null, 950]],
  ]);
  expect(sparksFor(live).latency).toEqual([900, null, 950]);
});

test('givesEmptyChartsWhenTheWindowHadNoCalls', () => {
  for (const metric of ['calls', 'spend', 'tokens', 'latency'] as const) {
    expect(chartFor(snapshot({ buckets: [bucket(1000), bucket(1060)] }), metric).x).toEqual([]);
    expect(chartFor(snapshot({ buckets: [] }), metric).x).toEqual([]);
  }
});

test('countsFailuresInTheSparklinesWithProviderErrors', () => {
  const live = snapshot({
    buckets: [bucket(1000, { sent: 5, refused: 1, failed: 2, serverErrors: 1 }), bucket(1060)],
  });
  const sparks = sparksFor(live);
  expect(sparks.calls).toEqual([8]);
  expect(sparks.failed).toEqual([3]);
  expect(sparks.refused).toEqual([1]);
});

test('keepsEveryLineAsLongAsTheBusyFixturesSettledBuckets', () => {
  const chart = chartFor(liveBusyFixture, 'calls');
  expect(chart.x).toHaveLength(179);
  expect(chart.lines.every((line) => line.values.length === 179)).toBe(true);
});
