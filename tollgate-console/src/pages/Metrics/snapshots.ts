import { liveBusyFixture } from '../../api/fixtures';
import type { Bucket, Flow, LiveSnapshot, Totals } from '../../api/types';

export function bucket(start: number, rest: Partial<Bucket> = {}): Bucket {
  return {
    start,
    sent: 0,
    refused: 0,
    failed: 0,
    clientErrors: 0,
    serverErrors: 0,
    clientClosed: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    p50Millis: null,
    p95Millis: null,
    ...rest,
  };
}

export function totals(rest: Partial<Totals> = {}): Totals {
  return {
    sent: 0,
    refused: 0,
    failed: 0,
    clientErrors: 0,
    serverErrors: 0,
    clientClosed: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    seconds: 900,
    p50Millis: null,
    p95Millis: null,
    ...rest,
  };
}

export function flow(rest: Partial<Flow> = {}): Flow {
  return {
    tenant: 'app',
    model: 'gpt',
    provider: 'openai',
    sent: 0,
    refused: {},
    failed: 0,
    clientErrors: 0,
    serverErrors: 0,
    clientClosed: 0,
    fallbacks: 0,
    inputTokens: 0,
    outputTokens: 0,
    cost: {},
    redacted: 0,
    lastSent: null,
    lastRefused: null,
    lastFallback: null,
    ...rest,
  };
}

export function snapshot(rest: Partial<LiveSnapshot> = {}): LiveSnapshot {
  return {
    ...liveBusyFixture,
    buckets: [],
    totals: totals(),
    previous: null,
    latency: { providers: {}, tenants: {}, models: {} },
    flows: [],
    guards: [],
    inFlight: {},
    limited: {},
    ...rest,
  };
}

export function idle(): LiveSnapshot {
  return snapshot({
    buckets: liveBusyFixture.buckets.map((item) => bucket(item.start)),
    previous: totals(),
  });
}
