import type { Bucket, LiveSnapshot } from '../api/types';
import { sumDecimals } from './describe';

export type Stats = {
  callsPerMinute: number;
  callSeries: number[];
  refused: number;
  refusedSeries: number[];
  spend: Record<string, string>;
  spendSeries: number[];
  p95Millis: number | null;
  latencySeries: number[];
};

export const minutes = 15;

function perMinute(
  live: LiveSnapshot,
  pick: (bucket: Bucket) => number,
  join: (total: number, value: number) => number,
): number[] {
  const series = Array.from({ length: minutes }, () => 0);
  const now = Date.parse(live.now) / 1000;
  for (const bucket of live.buckets) {
    const age = Math.floor((now - bucket.start) / 60);
    if (age >= 0 && age < minutes) {
      const index = minutes - 1 - age;
      series[index] = join(series[index] ?? 0, pick(bucket));
    }
  }
  return series;
}

function sum(total: number, value: number): number {
  return total + value;
}

function costOf(bucket: Bucket): number {
  return Object.values(bucket.cost).reduce((total, amount) => total + Number(amount), 0);
}

export function statsFor(live: LiveSnapshot): Stats {
  const callSeries = perMinute(live, (bucket) => bucket.sent + bucket.refused + bucket.failed, sum);
  const refusedSeries = perMinute(live, (bucket) => bucket.refused, sum);
  const now = Date.parse(live.now) / 1000;
  const recent = live.buckets.filter((bucket) => now - bucket.start < minutes * 60);
  const currencies = [...new Set(recent.flatMap((bucket) => Object.keys(bucket.cost)))].sort();
  return {
    callsPerMinute: callSeries[minutes - 1] ?? 0,
    callSeries,
    refused: refusedSeries.reduce(sum, 0),
    refusedSeries,
    spend: Object.fromEntries(
      currencies.map((currency) => [
        currency,
        sumDecimals(recent.flatMap((bucket) => bucket.cost[currency] ?? [])),
      ]),
    ),
    spendSeries: perMinute(live, costOf, sum),
    p95Millis: live.p95Millis,
    latencySeries: perMinute(live, (bucket) => bucket.p95Millis ?? 0, Math.max),
  };
}
