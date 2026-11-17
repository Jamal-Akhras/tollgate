import type { LiveSnapshot } from '../../api/types';
import { sumDecimals } from '../../flow/describe';

export type Line = { name: string; token: string; values: (number | null)[] };

export type Chart = { x: number[]; lines: Line[] };

export type Charts = { calls: Chart; tokens: Chart; spend: Chart; latency: Chart };

function rates(live: LiveSnapshot, pick: (bucket: LiveSnapshot['buckets'][number]) => number) {
  const seconds = live.bucketSeconds > 0 ? live.bucketSeconds : 5;
  return live.buckets.map((bucket) => pick(bucket) / seconds);
}

function spendPerMinute(live: LiveSnapshot): Chart {
  const minutes = new Map<number, Map<string, string[]>>();
  for (const bucket of live.buckets) {
    const minute = Math.floor(bucket.start / 60) * 60;
    const costs = minutes.get(minute) ?? new Map<string, string[]>();
    for (const [currency, amount] of Object.entries(bucket.cost)) {
      costs.set(currency, [...(costs.get(currency) ?? []), amount]);
    }
    minutes.set(minute, costs);
  }
  const x = [...minutes.keys()].sort((a, b) => a - b);
  const currencies = [
    ...new Set([...minutes.values()].flatMap((costs) => [...costs.keys()])),
  ].sort();
  return {
    x,
    lines: currencies.map((currency) => ({
      name: currency,
      token: '--accent',
      values: x.map((minute) => Number(sumDecimals(minutes.get(minute)?.get(currency) ?? []))),
    })),
  };
}

export function chartsFor(live: LiveSnapshot): Charts {
  const x = live.buckets.map((bucket) => bucket.start);
  return {
    calls: {
      x,
      lines: [
        { name: 'Sent', token: '--ok', values: rates(live, (bucket) => bucket.sent) },
        { name: 'Refused', token: '--accent', values: rates(live, (bucket) => bucket.refused) },
        { name: 'Failed', token: '--danger', values: rates(live, (bucket) => bucket.failed) },
      ],
    },
    tokens: {
      x,
      lines: [
        { name: 'Input', token: '--info', values: rates(live, (bucket) => bucket.inputTokens) },
        { name: 'Output', token: '--accent', values: rates(live, (bucket) => bucket.outputTokens) },
      ],
    },
    spend: spendPerMinute(live),
    latency: {
      x,
      lines: [
        { name: 'p50', token: '--info', values: live.buckets.map((bucket) => bucket.p50Millis) },
        { name: 'p95', token: '--accent', values: live.buckets.map((bucket) => bucket.p95Millis) },
      ],
    },
  };
}
