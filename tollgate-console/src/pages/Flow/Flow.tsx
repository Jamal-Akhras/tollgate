import { useEffect, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { BudgetState, ConfigView, LiveSnapshot } from '../../api/types';
import {
  checksFor,
  providersFor,
  tenantCalls,
  tenantCaption,
  type Check,
  type ProviderNode,
} from '../../flow/describe';
import { navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import { FlowCanvas, type TenantNode } from '../../ui/FlowCanvas';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import styles from './Flow.module.css';

const auditOffText = 'The audit trail is off in tollgate.yaml, so there is nothing to show here';

function useConfig() {
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [round, setRound] = useState(0);
  useEffect(() => {
    let stopped = false;
    get<ConfigView>('/config')
      .then((view) => {
        if (!stopped) {
          setConfig(view);
          setError(null);
        }
      })
      .catch((failure: unknown) => {
        if (!stopped) {
          setError(failure instanceof Error ? failure : new Error(String(failure)));
        }
      });
    return () => {
      stopped = true;
    };
  }, [round]);
  return { config, error, retry: () => setRound((value) => value + 1) };
}

function Loading() {
  return (
    <div class={styles.loading} aria-busy="true">
      <Skeleton height="72px" />
      <Skeleton height="240px" />
    </div>
  );
}

type ListProps = {
  tenants: TenantNode[];
  selected: string;
  checks: Check[];
  providers: ProviderNode[];
  onSelect: (tenant: string) => void;
};

function FlowList({ tenants, selected, checks, providers, onSelect }: ListProps) {
  return (
    <section class={styles.list} aria-label="Flow as a list">
      <label class={styles.pick}>
        Tenant
        <select value={selected} onChange={(event) => onSelect(event.currentTarget.value)}>
          {tenants.map((tenant) => (
            <option key={tenant.name} value={tenant.name}>
              {tenant.name}
            </option>
          ))}
        </select>
      </label>
      <h2 class={styles.heading}>Checks, in the order requests meet them</h2>
      <ol class={styles.items}>
        {checks.map((check) => (
          <li key={check.id}>
            {check.label}: {check.detail}
          </li>
        ))}
      </ol>
      <h2 class={styles.heading}>Providers</h2>
      <ul class={styles.items}>
        {providers.map((provider) => (
          <li key={provider.name}>
            {provider.name}: {provider.caption}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Diagram({
  config,
  live,
  budgets,
}: {
  config: ConfigView;
  live: LiveSnapshot | null;
  budgets: BudgetState[];
}) {
  const names = config.tenants
    .map((tenant) => tenant.name)
    .sort((a, b) => tenantCalls(live, b) - tenantCalls(live, a) || a.localeCompare(b));
  const asked = route.value.query.tenant;
  const selected = asked && names.includes(asked) ? asked : names[0];
  const tenant = config.tenants.find((item) => item.name === selected);
  if (!selected || !tenant) {
    return <Banner kind="info">No tenants are configured in tollgate.yaml.</Banner>;
  }
  const nodes = names.flatMap((name) => {
    const item = config.tenants.find((candidate) => candidate.name === name);
    return item ? [{ name, caption: tenantCaption(config, item), selected: name === selected }] : [];
  });
  const checks = checksFor(config, selected, budgets, live);
  const providers = providersFor(config, selected, live);
  const choose = (name: string) => navigate('flow', { tenant: name });
  return (
    <FlowCanvas
      tenants={nodes}
      policy={tenant.policy}
      checks={checks}
      providers={providers}
      flows={live?.flows.filter((flow) => flow.tenant === selected) ?? []}
      guards={live?.guards.filter((guard) => guard.tenant === selected) ?? []}
      now={live ? Date.parse(live.now) : Date.now()}
      onSelect={choose}
      fallback={
        <FlowList
          tenants={nodes}
          selected={selected}
          checks={checks}
          providers={providers}
          onSelect={choose}
        />
      }
    />
  );
}

export function Flow() {
  const { config, error, retry } = useConfig();
  const live = usePoll(() => get<LiveSnapshot>('/live', { minutes: 15 }), 2000);
  const budgets = usePoll(() => get<BudgetState[]>('/budgets'), 30_000);

  const liveError = live.error.value;
  const auditOff =
    config?.audit.mode === 'off' || (liveError instanceof ApiError && liveError.code === 'audit_off');
  const snapshot = auditOff ? null : live.data.value;
  const updating = live.data.value !== null && liveError === null && !auditOff;

  return (
    <div class={styles.page}>
      <PageHeader
        title="Flow"
        note="Last 15 minutes"
        error={auditOff ? null : liveError}
        updatedAt={live.updatedAt.value}
        now={Date.now()}
      >
        {updating && (
          <span class={styles.live}>
            <span class={styles.dot} />
            Live
          </span>
        )}
      </PageHeader>
      {auditOff && <Banner kind="info">{auditOffText}</Banner>}
      {snapshot?.skipped && (
        <Banner kind="info">Busy: the live view skipped some records</Banner>
      )}
      {error && !config && (
        <Banner
          kind="error"
          action={
            <button type="button" class={styles.button} onClick={retry}>
              Retry
            </button>
          }
        >
          {error.message}
        </Banner>
      )}
      {!config && !error && <Loading />}
      {config && <Diagram config={config} live={snapshot} budgets={budgets.data.value ?? []} />}
    </div>
  );
}
