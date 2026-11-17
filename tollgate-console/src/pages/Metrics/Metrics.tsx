import { get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { BudgetState, LiveSnapshot } from '../../api/types';
import { navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import { formatCount, formatDecimal } from '../../ui/format';
import { Meter } from '../../ui/Meter';
import { moneyText } from '../../ui/Money';
import { PageHeader } from '../../ui/PageHeader';
import { Segmented } from '../../ui/Segmented';
import { Skeleton } from '../../ui/Skeleton';
import { TimeSeries } from '../../ui/TimeSeries';
import { auditOffText, isAuditOff } from '../auditOff';
import styles from './Metrics.module.css';
import { chartsFor } from './series';

const windows = [5, 15, 60];
const defaultWindow = 15;
const note = 'Since this instance started, at most one hour. For longer periods see Usage.';

const options = windows.map((minutes) => ({ value: String(minutes), label: `${minutes} min` }));

function windowFrom(query: string | undefined): number {
  const minutes = Number(query);
  return windows.includes(minutes) ? minutes : defaultWindow;
}

function money(budget: BudgetState) {
  return (n: number) => moneyText({ amount: String(n), currency: budget.currency });
}

function spendFormat(value: number): string {
  return String(Math.round(value * 1_000_000) / 1_000_000);
}

function Budgets({ budgets }: { budgets: BudgetState[] }) {
  return (
    <section class={styles.panel} aria-label="Budgets">
      <h2 class={styles.heading}>Budgets</h2>
      {budgets.length === 0 && <p class={styles.muted}>No budgets are set.</p>}
      {budgets.map((budget) => (
        <div key={budget.tenant} class={styles.budget}>
          <Meter
            label={`${budget.tenant} · ${budget.period}`}
            value={Number(budget.spent)}
            limit={Number(budget.limit)}
            format={money(budget)}
          />
          <p class={styles.muted}>
            Reserved {moneyText({ amount: budget.reserved, currency: budget.currency })}, available{' '}
            {moneyText({ amount: budget.available, currency: budget.currency })}
          </p>
        </div>
      ))}
    </section>
  );
}

function InFlight({ live }: { live: LiveSnapshot }) {
  const providers = Object.entries(live.inFlight).sort(([a], [b]) => a.localeCompare(b));
  return (
    <section class={styles.panel} aria-label="Calls in flight">
      <h2 class={styles.heading}>Calls in flight now</h2>
      {providers.length === 0 && <p class={styles.muted}>Nothing is in flight.</p>}
      <ul class={styles.list}>
        {providers.map(([provider, count]) => (
          <li key={provider} class={styles.row}>
            <span>{provider}</span>
            <span class={styles.count}>{formatCount(count)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Panels({ minutes }: { minutes: number }) {
  const live = usePoll(() => get<LiveSnapshot>('/live', { minutes }), 5000);
  const budgets = usePoll(() => get<BudgetState[]>('/budgets'), 30_000);
  const snapshot = live.data.value;
  const error = live.error.value;
  const span = `last ${minutes} minutes`;

  if (isAuditOff(error)) {
    return <Banner kind="info">{auditOffText}</Banner>;
  }
  const charts = snapshot ? chartsFor(snapshot) : null;
  return (
    <>
      {error && (
        <Banner
          kind="error"
          action={
            <button type="button" class={styles.button} onClick={live.refresh}>
              Retry
            </button>
          }
        >
          {error.message}
        </Banner>
      )}
      {snapshot?.skipped && <Banner kind="info">Busy: the live view skipped some records</Banner>}
      {!charts && !error && (
        <div class={styles.loading} aria-busy="true">
          <Skeleton height="220px" />
          <Skeleton height="220px" />
        </div>
      )}
      {charts && snapshot && (
        <>
          <div class={styles.charts}>
            <TimeSeries
              label="Calls per second"
              unit="calls/s"
              window={span}
              x={charts.calls.x}
              lines={charts.calls.lines}
              format={formatDecimal}
              stacked
            />
            <TimeSeries
              label="Tokens per second"
              unit="tokens/s"
              window={span}
              x={charts.tokens.x}
              lines={charts.tokens.lines}
              format={formatDecimal}
            />
            <TimeSeries
              label="Spend per minute"
              unit="per minute"
              window={span}
              x={charts.spend.x}
              lines={charts.spend.lines}
              format={spendFormat}
            />
            <TimeSeries
              label="Latency"
              unit="ms"
              window={span}
              x={charts.latency.x}
              lines={charts.latency.lines}
              format={formatDecimal}
            />
          </div>
          <div class={styles.below}>
            <Budgets budgets={budgets.data.value ?? []} />
            <InFlight live={snapshot} />
          </div>
        </>
      )}
    </>
  );
}

export function Metrics() {
  const minutes = windowFrom(route.value.query.minutes);
  return (
    <div class={styles.page}>
      <PageHeader title="Metrics" note={note}>
        <Segmented
          label="Window"
          options={options}
          value={String(minutes)}
          onChange={(value) => navigate('metrics', { minutes: value })}
        />
      </PageHeader>
      <Panels key={minutes} minutes={minutes} />
    </div>
  );
}
