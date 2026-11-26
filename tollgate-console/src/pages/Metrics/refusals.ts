import type { LiveSnapshot } from '../../api/types';

export type Reason = { code: string | null; label: string; count: number };

export type GuardRow = { guard: string; allowed: number; refused: number; monitored: number };

const labels: Record<string, string> = {
  policy_denied: 'Refused by a guard',
  budget_exceeded: 'Over budget',
  too_large: 'Too large',
  invalid_data_class: 'Data class not allowed',
  redaction_timeout: 'Redaction timed out',
};

export function reasonLabel(code: string): string {
  return labels[code] ?? code;
}

function sum(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

export function reasons(live: LiveSnapshot): Reason[] {
  const counts = new Map<string, number>();
  for (const flow of live.flows) {
    for (const [code, count] of Object.entries(flow.refused)) {
      counts.set(code, (counts.get(code) ?? 0) + count);
    }
  }
  const rows: Reason[] = [...counts]
    .filter(([, count]) => count > 0)
    .map(([code, count]) => ({ code, label: reasonLabel(code), count }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
  const limited = sum(Object.values(live.limited));
  if (limited > 0) {
    rows.push({ code: null, label: 'Rate limited, since the start', count: limited });
  }
  return rows;
}

export function guardRows(live: LiveSnapshot): GuardRow[] {
  const rows = new Map<string, GuardRow>();
  for (const item of live.guards) {
    const row = rows.get(item.guard) ?? { guard: item.guard, allowed: 0, refused: 0, monitored: 0 };
    row.allowed += item.allowed;
    row.refused += item.refused;
    row.monitored += item.monitored;
    rows.set(item.guard, row);
  }
  return [...rows.values()].sort((a, b) => b.refused - a.refused || a.guard.localeCompare(b.guard));
}

export function redacted(live: LiveSnapshot): number {
  return sum(live.flows.map((flow) => flow.redacted));
}
