import type { Bucket, LiveSnapshot } from '../../api/types';
import { failedOf } from './figures';

export type Metric = 'calls' | 'spend' | 'tokens' | 'latency';

export type Line = { name: string; token: string; values: (number | null)[] };

export type Chart = { x: number[]; lines: Line[]; unit: string; stacked: boolean; bars: boolean };

export type Sparks = {
  calls: number[];
  failed: number[];
  refused: number[];
  spend: number[];
  latency: (number | null)[];
};

const currencyTokens = ['--accent', '--info', '--ok', '--danger'];

export function settled(live: LiveSnapshot): Bucket[] {
  return live.buckets.slice(0, -1);
}

function perMinuteScale(live: LiveSnapshot): number {
  return 60 / (live.bucketSeconds > 0 ? live.bucketSeconds : 5);
}

function quiet(buckets: Bucket[]): boolean {
  return buckets.every((bucket) => bucket.sent + bucket.refused + bucket.failed === 0);
}

function costOf(bucket: Bucket, currency: string): number {
  return Number(bucket.cost[currency] ?? 0);
}

function spendChart(buckets: Bucket[], x: number[], scale: number): Chart {
  const currencies = [...new Set(buckets.flatMap((bucket) => Object.keys(bucket.cost)))].sort();
  const names = currencies.length === 0 ? ['Spend'] : currencies;
  const unit = currencies.length === 1 ? `${currencies[0]} per minute` : 'per minute';
  return {
    x,
    unit,
    stacked: false,
    bars: true,
    lines: names.map((name, index) => ({
      name,
      token: currencyTokens[index % currencyTokens.length] ?? '--accent',
      values: buckets.map((bucket) => costOf(bucket, name) * scale),
    })),
  };
}

export function chartFor(live: LiveSnapshot, metric: Metric): Chart {
  const buckets = settled(live);
  const empty = quiet(buckets);
  const x = empty ? [] : buckets.map((bucket) => bucket.start);
  const scale = perMinuteScale(live);
  const rate = (pick: (bucket: Bucket) => number) =>
    buckets.map((bucket) => pick(bucket) * scale);
  if (metric === 'calls') {
    return {
      x,
      unit: 'calls per minute',
      stacked: true,
      bars: true,
      lines: [
        {
          name: 'Answered',
          token: '--ok',
          values: rate((bucket) =>
            Math.max(0, bucket.sent - bucket.clientErrors - bucket.serverErrors),
          ),
        },
        { name: 'Client errors', token: '--info', values: rate((bucket) => bucket.clientErrors) },
        { name: 'Refused', token: '--accent', values: rate((bucket) => bucket.refused) },
        { name: 'Failed', token: '--danger', values: rate(failedOf) },
      ],
    };
  }
  if (metric === 'tokens') {
    return {
      x,
      unit: 'tokens per minute',
      stacked: true,
      bars: true,
      lines: [
        { name: 'Input', token: '--info', values: rate((bucket) => bucket.inputTokens) },
        { name: 'Output', token: '--accent', values: rate((bucket) => bucket.outputTokens) },
      ],
    };
  }
  if (metric === 'spend') {
    return spendChart(buckets, x, scale);
  }
  return {
    x,
    unit: 'ms',
    stacked: false,
    bars: false,
    lines: [
      { name: 'p50', token: '--info', values: buckets.map((bucket) => bucket.p50Millis) },
      { name: 'p95', token: '--accent', values: buckets.map((bucket) => bucket.p95Millis) },
    ],
  };
}

export function sparksFor(live: LiveSnapshot): Sparks {
  const buckets = settled(live);
  return {
    calls: buckets.map((bucket) => bucket.sent + bucket.refused + bucket.failed),
    failed: buckets.map(failedOf),
    refused: buckets.map((bucket) => bucket.refused),
    spend: buckets.map((bucket) =>
      Object.values(bucket.cost).reduce((total, amount) => total + Number(amount), 0),
    ),
    latency: buckets.map((bucket) => bucket.p95Millis),
  };
}
