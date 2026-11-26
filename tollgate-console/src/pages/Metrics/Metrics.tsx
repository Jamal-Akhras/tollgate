import { useEffect, useRef } from 'preact/hooks';
import { get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { BudgetState, LiveSnapshot } from '../../api/types';
import { href, navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import { PageHeader } from '../../ui/PageHeader';
import { RetryBanner } from '../../ui/RetryBanner';
import { Segmented } from '../../ui/Segmented';
import { Skeleton } from '../../ui/Skeleton';
import { auditOffText, isAuditOff } from '../auditOff';
import { useConfig } from '../useConfig';
import { Budgets } from './Budgets';
import { Drivers } from './Drivers';
import styles from './Metrics.module.css';
import { Overtime } from './Overtime';
import { Providers } from './Providers';
import { Stopped } from './Stopped';
import { Tiles } from './Tiles';
import { stepFor, windowFrom, windows } from './windows';

type Answer = { minutes: number; live: LiveSnapshot };

const options = windows.map((minutes) => ({ value: String(minutes), label: `${minutes} min` }));

const tileSlots = ['calls', 'failed', 'refused', 'spend', 'latency'];

function Loading() {
  return (
    <div class={styles.loading} aria-busy="true">
      <div class={styles.tiles}>
        {tileSlots.map((slot) => (
          <Skeleton key={slot} height="150px" />
        ))}
      </div>
      <Skeleton height="360px" />
    </div>
  );
}

export function Metrics() {
  const minutes = windowFrom(route.value.query.minutes);
  const live = usePoll<Answer>(
    () =>
      get<LiveSnapshot>('/live', { minutes, step: stepFor(minutes) }).then((snapshot) => ({
        minutes,
        live: snapshot,
      })),
    5000,
  );
  const budgets = usePoll(() => get<BudgetState[]>('/budgets'), 30_000);
  const { config } = useConfig();

  const shownWindow = useRef(minutes);
  useEffect(() => {
    if (shownWindow.current !== minutes) {
      shownWindow.current = minutes;
      live.refresh();
    }
  }, [minutes]);

  const error = live.error.value;
  const auditOff = isAuditOff(error);
  const answer = auditOff ? null : live.data.value;
  const updating = answer !== null && error === null;
  const providers = config?.providers.map((provider) => provider.name) ?? [];

  return (
    <div class={styles.page}>
      <PageHeader
        title="Metrics"
        note={
          <>
            Since this instance started, at most one hour. For longer periods see{' '}
            <a href={href('usage')}>Usage</a>.
          </>
        }
        error={auditOff ? null : error}
        updatedAt={live.updatedAt.value}
        now={Date.now()}
      >
        {updating && (
          <span class={styles.live}>
            <span class={styles.dot} />
            Live
          </span>
        )}
        <Segmented
          label="Window"
          options={options}
          value={String(minutes)}
          onChange={(value) => navigate('metrics', { minutes: value })}
        />
      </PageHeader>
      {auditOff && <Banner kind="info">{auditOffText}</Banner>}
      {!auditOff && error && <RetryBanner error={error} onRetry={live.refresh} />}
      {answer?.live.skipped && (
        <Banner kind="info">Busy: the live view skipped some records</Banner>
      )}
      {!auditOff && !answer && !error && <Loading />}
      {answer && (
        <>
          <Tiles live={answer.live} minutes={answer.minutes} />
          <Providers live={answer.live} configured={providers} />
          <Overtime live={answer.live} minutes={answer.minutes} />
          <Stopped live={answer.live} />
          <Drivers live={answer.live} />
          <Budgets
            budgets={budgets.data.value}
            error={budgets.error.value}
            onRetry={budgets.refresh}
            live={answer.live}
          />
          <p class={styles.footer}>
            Longer periods are in <a href={href('usage')}>Usage</a>. Prometheus metrics are on the
            management port; a Grafana dashboard is in docs/grafana.
          </p>
        </>
      )}
    </div>
  );
}
