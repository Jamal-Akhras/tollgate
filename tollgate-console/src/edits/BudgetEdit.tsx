import { useState } from 'preact/hooks';
import { put, segment } from '../api/client';
import type { BudgetChange, BudgetState, ConfigChange, ConfigView } from '../api/types';
import { moneyText } from '../ui/Money';
import { Segmented } from '../ui/Segmented';
import styles from './BudgetEdit.module.css';
import { ConfirmEdit } from './ConfirmEdit';
import { runEdit } from './edit';
import { budgetLines } from './yaml';

type Period = BudgetChange['period'];

type BudgetEditProps = {
  config: ConfigView;
  tenant: string;
  state: BudgetState | null;
  onChanged: () => void;
  onClose: () => void;
};

const periods: { value: Period; label: string }[] = [
  { value: 'daily', label: 'Daily' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'total', label: 'Total' },
];

const validLimit = /^\d+(\.\d{1,6})?$/;

function spentWord(period: string): string {
  return period === 'daily' ? 'today' : period === 'total' ? 'in total' : 'this month';
}

function endWord(period: string): string {
  return period === 'daily'
    ? 'the day ends'
    : period === 'total'
      ? 'the limit is raised'
      : 'the month ends';
}

function asPeriod(value: string): Period {
  return periods.find((item) => item.value === value)?.value ?? 'monthly';
}

export function BudgetEdit({ config, tenant, state, onChanged, onClose }: BudgetEditProps) {
  const current = config.tenants.find((item) => item.name === tenant)?.budget ?? null;
  const [limit, setLimit] = useState(current?.limit ?? '');
  const [period, setPeriod] = useState<Period>(asPeriod(current?.period ?? 'monthly'));

  const currency =
    current?.currency ??
    state?.currency ??
    config.tenants.find((item) => item.budget)?.budget?.currency ??
    'USD';
  const valid = validLimit.test(limit);
  const money = (amount: string) => moneyText({ amount, currency });
  const was = state?.period ?? current?.period ?? 'monthly';

  let warning: string | undefined;
  let note: string | undefined;
  let confirmLabel = 'Set budget';
  if (valid && state && Number(limit) < Number(state.spent)) {
    warning = `${tenant} has already spent ${money(state.spent)} ${spentWord(was)}. With a limit of ${money(limit)}, every priced call from ${tenant} is refused with budget_exceeded as soon as this is written, until ${endWord(period)}.`;
    confirmLabel = `Set ${money(limit)} anyway`;
  } else if (current && current.period !== period) {
    warning =
      'Changing the period starts the count from zero. Tollgate sums the audit trail again for the new period when it next restarts.';
  } else if (!current) {
    note = 'The count starts at zero now. Calls made before this are not counted.';
  }

  const status = state
    ? `Spent ${spentWord(state.period)}: ${money(state.spent)}. Held for calls in flight: ${money(state.reserved)}.`
    : current
      ? null
      : 'No budget yet.';

  return (
    <ConfirmEdit
      title={`Budget for ${tenant}`}
      lines={budgetLines(tenant, current, { limit, period })}
      config={config}
      warning={warning}
      note={note}
      disabled={!valid}
      confirmLabel={confirmLabel}
      onConfirm={() =>
        runEdit(
          () =>
            put<ConfigChange>(
              `/tenants/${segment(tenant)}/budget`,
              { limit, period } satisfies BudgetChange,
              config.version,
            ),
          { onChanged },
        )
      }
      onClose={onClose}
    >
      <div class={styles.form}>
        <div class={styles.field}>
          <label for="budget-limit" class={styles.label}>
            Limit
          </label>
          <div class={styles.row}>
            <input
              id="budget-limit"
              class={styles.input}
              type="text"
              inputMode="decimal"
              autoComplete="off"
              value={limit}
              aria-invalid={limit !== '' && !valid}
              aria-describedby={limit !== '' && !valid ? 'budget-limit-problem' : undefined}
              onInput={(event) => setLimit(event.currentTarget.value)}
            />
            <span class={styles.currency}>{currency}</span>
          </div>
          {limit !== '' && !valid && (
            <p id="budget-limit-problem" class={styles.problem}>
              A number such as 150 or 49.50
            </p>
          )}
        </div>
        <div class={styles.field}>
          <span class={styles.label}>Period</span>
          <Segmented
            label="Period"
            options={periods}
            value={period}
            onChange={(value) => setPeriod(asPeriod(value))}
          />
        </div>
      </div>
      {status && <p class={styles.status}>{status}</p>}
    </ConfirmEdit>
  );
}
