import type { LiveSnapshot } from '../../api/types';
import { enoughCalls, failedOf, share } from './figures';

export type ProviderStatus = 'ok' | 'degraded' | 'idle';

export type ProviderRow = {
  name: string;
  status: ProviderStatus;
  calls: number;
  failedShare: number | null;
  p95Millis: number | null;
  fallbacks: number;
  inFlight: number;
};

export const statusText: Record<ProviderStatus, string> = {
  ok: 'OK',
  degraded: 'Degraded',
  idle: 'Idle',
};

const degradedAbove = 5;

function statusOf(calls: number, failedShare: number | null): ProviderStatus {
  if (calls === 0) {
    return 'idle';
  }
  return failedShare !== null && failedShare > degradedAbove && calls >= enoughCalls
    ? 'degraded'
    : 'ok';
}

export function providerRows(live: LiveSnapshot, configured: string[]): ProviderRow[] {
  const names = new Set([
    ...configured,
    ...live.flows.map((flow) => flow.provider),
    ...Object.keys(live.inFlight),
  ]);
  return [...names]
    .map((name) => {
      const flows = live.flows.filter((flow) => flow.provider === name);
      const sent = flows.reduce((total, flow) => total + flow.sent, 0);
      const calls = sent + flows.reduce((total, flow) => total + flow.failed, 0);
      const failed = flows.reduce((total, flow) => total + failedOf(flow), 0);
      const failedShare = share(failed, calls);
      const known = live.latency.providers[name];
      return {
        name,
        status: statusOf(calls, failedShare),
        calls,
        failedShare,
        p95Millis: known && sent >= enoughCalls ? known.p95Millis : null,
        fallbacks: flows.reduce((total, flow) => total + flow.fallbacks, 0),
        inFlight: live.inFlight[name] ?? 0,
      };
    })
    .sort((a, b) => b.calls - a.calls || a.name.localeCompare(b.name));
}
