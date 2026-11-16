import { useEffect, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { BudgetState, ConfigView, LiveSnapshot } from '../../api/types';
import {
  blockedProviders,
  checksFor,
  previewProviders,
  providersFor,
  tenantCalls,
  tenantCaption,
} from '../../flow/describe';
import { describeRule } from '../../flow/rule';
import { navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import { FlowCanvas } from '../../ui/FlowCanvas';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import { HowToChange } from '../Config/HowToChange';
import type { Snippet } from '../Config/snippets';
import { FirstRun } from './FirstRun';
import styles from './Flow.module.css';
import { FlowTable } from './FlowTable';
import { Legend } from './Legend';
import { usePreview } from './Preview';
import { RuleCard } from './RuleCard';
import { StatTiles } from './StatTiles';

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

type DiagramProps = {
  config: ConfigView;
  live: LiveSnapshot | null;
  budgets: BudgetState[];
  withTollgate: boolean;
  table: boolean;
  onChanged: () => void;
};

function Diagram({ config, live, budgets, withTollgate, table, onChanged }: DiagramProps) {
  const [open, setOpen] = useState<{ tenant: string; id: string } | null>(null);
  const [snippet, setSnippet] = useState<Snippet | null>(null);
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
  const own = providersFor(config, selected, live);
  const providers = withTollgate ? own : previewProviders(config, selected, live);
  const refusedProviders = blockedProviders(config, selected);
  const choose = (name: string) => navigate('flow', { tenant: name });

  const openId = open && open.tenant === selected ? open.id : null;
  const check = checks.find((item) => item.id === openId);
  const rule = check ? describeRule(config, selected, check, live, budgets) : null;
  const noCalls = live !== null && !live.flows.some((flow) => flow.tenant === selected);
  const tables = (
    <FlowTable
      tenants={names}
      selected={selected}
      live={live}
      checks={checks}
      providers={own}
      onSelect={choose}
    />
  );

  return (
    <>
      {!withTollgate && (
        <Banner kind="info">
          <strong>Without Tollgate.</strong> Your tools would call providers directly with their own
          keys. None of these rules would run, nothing would be recorded
          {refusedProviders.length > 0 &&
            `, and ${refusedProviders.map((p) => `${p.name} in ${p.region ?? 'no region'}`).join(' and ')} would be reachable`}
          . This is a preview; nothing has changed.
        </Banner>
      )}
      {table ? (
        tables
      ) : (
        <FlowCanvas
          tenants={nodes}
          policy={tenant.policy}
          checks={checks}
          providers={providers}
          flows={live?.flows.filter((flow) => flow.tenant === selected) ?? []}
          guards={live?.guards.filter((guard) => guard.tenant === selected) ?? []}
          now={live ? Date.parse(live.now) : Date.now()}
          preview={!withTollgate}
          openCheck={openId}
          onCheck={(id) => setOpen(openId === id ? null : { tenant: selected, id })}
          onSelect={choose}
          legend={<Legend />}
          fallback={tables}
        />
      )}
      <div class={styles.below}>
        <div class={styles.panel}>
          {rule && check ? (
            <RuleCard
              rule={rule}
              tenant={selected}
              guard={check.guard}
              onChange={setSnippet}
              onClose={() => setOpen(null)}
            />
          ) : (
            noCalls && <FirstRun config={config} tenant={selected} />
          )}
        </div>
      </div>
      {live && <StatTiles live={live} />}
      {snippet && (
        <HowToChange snippet={snippet} onClose={() => setSnippet(null)} onChanged={onChanged} />
      )}
    </>
  );
}

export function Flow() {
  const { config, error, retry } = useConfig();
  const live = usePoll(() => get<LiveSnapshot>('/live', { minutes: 15 }), 2000);
  const budgets = usePoll(() => get<BudgetState[]>('/budgets'), 30_000);
  const withTollgate = usePreview();
  const [table, setTable] = useState(false);

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
        <button
          type="button"
          class={styles.button}
          aria-pressed={table}
          onClick={() => setTable(!table)}
        >
          {table ? 'Show as diagram' : 'Show as table'}
        </button>
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
      {config && (
        <Diagram
          config={config}
          live={snapshot}
          budgets={budgets.data.value ?? []}
          withTollgate={withTollgate}
          table={table}
          onChanged={retry}
        />
      )}
    </div>
  );
}
