import type { Flow, LiveSnapshot } from '../../api/types';
import { callsOf, refusals, sumDecimals } from '../../flow/describe';
import { enoughCalls, failedOf, share } from './figures';

export type By = 'tenant' | 'model';

export type DriverRow = {
  name: string;
  other: boolean;
  calls: number;
  failedShare: number | null;
  refused: number;
  inputTokens: number;
  outputTokens: number;
  cost: Record<string, string>;
  p95Millis: number | null;
};

const shown = 10;

function rowOf(
  name: string,
  other: boolean,
  flows: Flow[],
  currencies: string[],
  p95Millis: number | null,
): DriverRow {
  const calls = callsOf(flows);
  const failed = flows.reduce((total, flow) => total + failedOf(flow), 0);
  return {
    name,
    other,
    calls,
    failedShare: share(failed, calls),
    refused: flows.reduce((total, flow) => total + refusals(flow), 0),
    inputTokens: flows.reduce((total, flow) => total + flow.inputTokens, 0),
    outputTokens: flows.reduce((total, flow) => total + flow.outputTokens, 0),
    cost: Object.fromEntries(
      currencies.map((currency) => [
        currency,
        sumDecimals(flows.flatMap((flow) => flow.cost[currency] ?? [])),
      ]),
    ),
    p95Millis,
  };
}

export function driverRows(live: LiveSnapshot, by: By): DriverRow[] {
  const groups = new Map<string, Flow[]>();
  for (const flow of live.flows) {
    const name = by === 'tenant' ? flow.tenant : flow.model;
    groups.set(name, [...(groups.get(name) ?? []), flow]);
  }
  const currencies = [...new Set(live.flows.flatMap((flow) => Object.keys(flow.cost)))].sort();
  const known = by === 'tenant' ? live.latency.tenants : live.latency.models;
  const rows = [...groups].map(([name, flows]) => {
    const responded = flows.reduce((total, flow) => total + flow.sent, 0);
    const percentiles = known[name];
    const p95 = percentiles && responded >= enoughCalls ? percentiles.p95Millis : null;
    return { row: rowOf(name, false, flows, currencies, p95), flows };
  });
  rows.sort((a, b) => b.row.calls - a.row.calls || a.row.name.localeCompare(b.row.name));
  const top = rows.slice(0, shown).map((item) => item.row);
  const rest = rows.slice(shown).flatMap((item) => item.flows);
  return rest.length === 0 ? top : [...top, rowOf('Other', true, rest, currencies, null)];
}
