import type { BudgetState, LiveSnapshot } from '../../api/types';
import { Meter } from '../../ui/Meter';
import { moneyText } from '../../ui/Money';
import { RetryBanner } from '../../ui/RetryBanner';
import { Skeleton } from '../../ui/Skeleton';
import { budgetRows } from './burn';
import { amountText } from './figures';
import styles from './Metrics.module.css';

function money(budget: BudgetState) {
  return (n: number) => moneyText({ amount: String(n), currency: budget.currency, short: true });
}

type BudgetsProps = {
  budgets: BudgetState[] | null;
  error: Error | null;
  onRetry: () => void;
  live: LiveSnapshot;
};

export function Budgets({ budgets, error, onRetry, live }: BudgetsProps) {
  const rows = budgetRows(budgets ?? [], live);
  return (
    <section class={styles.section} aria-label="Budgets">
      <h2 class={styles.heading}>Budgets</h2>
      {error && <RetryBanner error={error} onRetry={onRetry} />}
      {budgets === null && !error && <Skeleton height="64px" />}
      {budgets !== null && rows.length === 0 && <p class={styles.muted}>No budgets are set.</p>}
      <div class={styles.budgets}>
        {rows.map(({ budget, perHour, reach }) => (
          <div key={budget.tenant} class={styles.budget}>
            <Meter
              label={`${budget.tenant} · ${budget.period}`}
              value={Number(budget.spent)}
              limit={Number(budget.limit)}
              format={money(budget)}
            />
            <p class={styles.muted}>
              Reserved{' '}
              {moneyText({ amount: budget.reserved, currency: budget.currency, short: true })}
              {perHour > 0
                ? ` · ${amountText(perHour, budget.currency)} an hour at this pace`
                : ' · No spend in this window'}
            </p>
            {reach !== null && (
              <p class={styles.muted}>Reaches the limit in about {reach} at this pace</p>
            )}
            {reach === null && perHour > 0 && <p class={styles.muted}>The limit is reached</p>}
          </div>
        ))}
      </div>
    </section>
  );
}
