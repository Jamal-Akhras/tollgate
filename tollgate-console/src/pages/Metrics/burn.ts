import type { BudgetState, LiveSnapshot } from '../../api/types';

export type BudgetRow = {
  budget: BudgetState;
  used: number;
  perHour: number;
  reach: string | null;
};

export function reachText(hours: number): string {
  if (hours < 1) {
    return `${Math.max(1, Math.round(hours * 60))} min`;
  }
  if (hours < 48) {
    return `${Math.round(hours)} h`;
  }
  return `${Math.round(hours / 24)} days`;
}

function usedOf(budget: BudgetState): number {
  const limit = Number(budget.limit);
  return limit > 0 ? Number(budget.spent) / limit : 0;
}

export function budgetRows(budgets: BudgetState[], live: LiveSnapshot): BudgetRow[] {
  const seconds = live.totals.seconds;
  return budgets
    .map((budget) => {
      const spent = live.flows
        .filter((flow) => flow.tenant === budget.tenant)
        .reduce((total, flow) => total + Number(flow.cost[budget.currency] ?? 0), 0);
      const perHour = seconds > 0 ? (spent * 3600) / seconds : 0;
      const available = Number(budget.available);
      const reach = perHour > 0 && available > 0 ? reachText(available / perHour) : null;
      return { budget, used: usedOf(budget), perHour, reach };
    })
    .sort((a, b) => b.used - a.used || a.budget.tenant.localeCompare(b.budget.tenant));
}
