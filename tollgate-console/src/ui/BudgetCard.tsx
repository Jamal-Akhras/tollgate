import { get } from '../api/client';
import { usePoll } from '../api/poll';
import type { BudgetState } from '../api/types';
import styles from './BudgetCard.module.css';
import { Meter } from './Meter';
import { moneyText } from './Money';

const periods: Record<string, string> = {
  daily: 'today',
  weekly: 'this week',
  monthly: 'this month',
};

function share(budget: BudgetState): number {
  const limit = Number(budget.limit);
  return limit > 0 ? Number(budget.spent) / limit : 0;
}

export function tightest(budgets: BudgetState[]): BudgetState | null {
  let best: BudgetState | null = null;
  for (const budget of budgets) {
    if (!best || share(budget) > share(best)) {
      best = budget;
    }
  }
  return best;
}

export function BudgetCard() {
  const budgets = usePoll(() => get<BudgetState[]>('/budgets'), 30_000);
  const budget = tightest(budgets.data.value ?? []);
  if (!budget) {
    return null;
  }
  const money = (n: number) => moneyText({ amount: String(n), currency: budget.currency, short: true });
  return (
    <div class={styles.card}>
      <Meter
        label={`${budget.tenant} · ${periods[budget.period] ?? budget.period}`}
        value={Number(budget.spent)}
        limit={Number(budget.limit)}
        format={money}
      />
    </div>
  );
}
