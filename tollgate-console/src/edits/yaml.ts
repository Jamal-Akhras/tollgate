import type { BudgetChange, BudgetView, GuardMode, KeyView } from '../api/types';

export type YamlLines = { path: string; removed: string[]; added: string[] };

export function guardModeLines(
  policy: string,
  index: number,
  guard: string,
  mode: GuardMode['mode'],
): YamlLines {
  const path = `policies.${policy}.guards[${index}] (${guard})`;
  const line = 'mode: "monitor"';
  return mode === 'monitor'
    ? { path, removed: [], added: [line] }
    : { path, removed: [line], added: [] };
}

export function budgetLines(
  tenant: string,
  current: BudgetView | null,
  next: BudgetChange,
): YamlLines {
  return {
    path: `tenants.${tenant}.budget`,
    removed: current ? [`limit: ${current.limit}`, `period: "${current.period}"`] : [],
    added: [`limit: ${next.limit}`, `period: "${next.period}"`],
  };
}

export function newKeyLines(tenant: string): YamlLines {
  return {
    path: 'keys',
    removed: [],
    added: ['- id: (made by Tollgate)', `tenant: "${tenant}"`, "sha256: (the new key's hash)"],
  };
}

export function revokedKeyLines(key: KeyView): YamlLines {
  return {
    path: 'keys',
    removed: [`- id: "${key.id}"`, `tenant: "${key.tenant}"`, 'sha256: (its hash)'],
    added: [],
  };
}
