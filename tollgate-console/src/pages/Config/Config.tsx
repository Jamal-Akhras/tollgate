import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { get } from '../../api/client';
import type {
  BudgetState,
  BudgetView,
  ConfigView,
  GuardView,
  LimitsView,
  PolicyView,
} from '../../api/types';
import { BudgetEdit } from '../../edits/BudgetEdit';
import { CreateKey } from '../../edits/CreateKey';
import { GuardSwitch } from '../../edits/GuardSwitch';
import { HowToChange, useReload } from '../../edits/HowToChange';
import { RevokeKey } from '../../edits/RevokeKey';
import { editsOn } from '../../edits/edit';
import {
  forSection,
  removeKey,
  setBudget,
  type SectionName,
  type Snippet,
} from '../../edits/snippets';
import { href } from '../../router';
import { Badge } from '../../ui/Badge';
import { Banner } from '../../ui/Banner';
import { DataTable, type Column } from '../../ui/DataTable';
import { Hash } from '../../ui/Hash';
import { Money } from '../../ui/Money';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import { useConfig } from '../useConfig';
import styles from './Config.module.css';

type Change = (snippet: Snippet) => void;
type SectionProps = { config: ConfigView; change: Change };
type Budgets = { budgets: BudgetState[]; onChanged: () => void };

const offText =
  'Changes are made in tollgate.yaml; each control here shows what to write. Set TOLLGATE_ADMIN_EDITS=file to let the console write them.';
const onText =
  "The console can change guards' modes, budgets and keys. It rewrites tollgate.yaml and keeps each version in config-history.";

function anchor(kind: string, name: string): string {
  return `${kind}-${name}`;
}

function Jump({ kind, name }: { kind: string; name: string }) {
  return (
    <a
      href={href('config')}
      onClick={(event) => {
        event.preventDefault();
        document.getElementById(anchor(kind, name))?.scrollIntoView?.();
      }}
    >
      {name}
    </a>
  );
}

function Name({ kind, name }: { kind: string; name: string }) {
  return (
    <span id={anchor(kind, name)} class={styles.name}>
      {name}
    </span>
  );
}

function List({ items }: { items: string[] }) {
  return <>{items.length === 0 ? <span class={styles.none}>none</span> : items.join(', ')}</>;
}

function Section({
  title,
  name,
  config,
  change,
  children,
}: SectionProps & { title: string; name: SectionName; children: ComponentChildren }) {
  return (
    <section class={styles.section} aria-label={title}>
      <div class={styles.head}>
        <h2 class={styles.title}>{title}</h2>
        <button
          type="button"
          class={styles.button}
          aria-label={`How do I change ${title.toLowerCase()}?`}
          onClick={() => change(forSection(name, config))}
        >
          How do I change this?
        </button>
      </div>
      {children}
    </section>
  );
}

function budgetText(budget: BudgetView | null) {
  return budget ? (
    <>
      <Money amount={budget.limit} currency={budget.currency} /> {budget.period}
    </>
  ) : (
    <span class={styles.none}>none</span>
  );
}

function limitsText(limits: LimitsView): string {
  const parts = [
    limits.requestsPerMinute !== null && `${limits.requestsPerMinute} requests/min`,
    limits.tokensPerMinute !== null && `${limits.tokensPerMinute} tokens/min`,
    limits.concurrent !== null && `${limits.concurrent} at once`,
  ].filter(Boolean);
  return parts.length === 0 ? 'none' : parts.join(', ');
}

function Tenants({ config, change, budgets, onChanged }: SectionProps & Budgets) {
  const [changing, setChanging] = useState<string | null>(null);
  const columns: Column<ConfigView['tenants'][number]>[] = [
    {
      key: 'name',
      header: 'Tenant',
      cell: (tenant) => (
        <span id={anchor('tenant', tenant.name)} class={styles.name}>
          <a href={href('flow', { tenant: tenant.name })}>{tenant.name}</a>
        </span>
      ),
    },
    { key: 'class', header: 'Data class', cell: (tenant) => tenant.dataClass ?? '-', priority: 2 },
    {
      key: 'policy',
      header: 'Policy',
      cell: (tenant) => <Jump kind="policy" name={tenant.policy} />,
    },
    {
      key: 'models',
      header: 'Models',
      cell: (tenant) => <List items={tenant.models} />,
      priority: 2,
    },
    {
      key: 'budget',
      header: 'Budget',
      cell: (tenant) => (
        <span class={styles.budget}>
          {budgetText(tenant.budget)}
          <button
            type="button"
            class={styles.button}
            aria-label={`${tenant.budget ? 'Change budget of' : 'Set a budget for'} ${tenant.name}`}
            onClick={() =>
              editsOn(config)
                ? setChanging(tenant.name)
                : change(setBudget(tenant.name, tenant.budget))
            }
          >
            {tenant.budget ? 'Change' : 'Set a budget'}
          </button>
        </span>
      ),
    },
    { key: 'limits', header: 'Limits', cell: (tenant) => limitsText(tenant.limits), priority: 3 },
    {
      key: 'user',
      header: 'User header',
      cell: (tenant) => (tenant.attributesUsers ? 'set' : 'not set'),
      priority: 3,
    },
  ];
  return (
    <Section title="Tenants" name="tenants" config={config} change={change}>
      <DataTable
        label="Tenants"
        columns={columns}
        rows={config.tenants}
        rowKey={(tenant) => tenant.name}
      />
      {changing && (
        <BudgetEdit
          config={config}
          tenant={changing}
          state={budgets.find((item) => item.tenant === changing) ?? null}
          onChanged={onChanged}
          onClose={() => setChanging(null)}
        />
      )}
    </Section>
  );
}

function Keys({ config, change, onChanged }: SectionProps & { onChanged: () => void }) {
  const [creating, setCreating] = useState(false);
  const [revoking, setRevoking] = useState<ConfigView['keys'][number] | null>(null);
  const columns: Column<ConfigView['keys'][number]>[] = [
    { key: 'id', header: 'Key ID', cell: (key) => <Hash value={key.id} /> },
    { key: 'tenant', header: 'Tenant', cell: (key) => <Jump kind="tenant" name={key.tenant} /> },
    {
      key: 'revoke',
      header: 'Revoke',
      cell: (key) => (
        <button
          type="button"
          class={styles.button}
          aria-label={`Revoke key ${key.id}`}
          onClick={() =>
            editsOn(config) ? setRevoking(key) : change(removeKey(key.id, key.tenant))
          }
        >
          Revoke
        </button>
      ),
    },
  ];
  return (
    <Section title="Keys" name="keys" config={config} change={change}>
      <div>
        <button
          type="button"
          class={styles.button}
          onClick={() => (editsOn(config) ? setCreating(true) : change(forSection('keys', config)))}
        >
          Create a key
        </button>
      </div>
      <DataTable
        label="Keys"
        columns={columns}
        rows={config.keys}
        rowKey={(key) => key.id}
        empty={<p class={styles.empty}>No keys yet.</p>}
      />
      {creating && (
        <CreateKey config={config} onChanged={onChanged} onClose={() => setCreating(false)} />
      )}
      {revoking && (
        <RevokeKey
          config={config}
          keyView={revoking}
          onChanged={onChanged}
          onClose={() => setRevoking(null)}
        />
      )}
    </Section>
  );
}

function settings(guard: GuardView): string {
  const parts = [
    guard.maxBytes !== null && `max ${guard.maxBytes} bytes`,
    guard.regions && `regions ${describe(guard.regions)}`,
    guard.allow && `allow ${guard.allow.join(', ')}`,
    guard.refuse && `refuse ${guard.refuse.join(', ')}`,
  ].filter(Boolean);
  return parts.join('; ');
}

function describe(map: Record<string, string[]>): string {
  const entries = Object.entries(map);
  return entries.length === 0
    ? 'none'
    : entries.map(([key, values]) => `${key}: ${values.join(', ')}`).join('; ');
}

function Guards({
  policy,
  config,
  onChanged,
}: {
  policy: PolicyView;
  config: ConfigView;
  onChanged: () => void;
}) {
  if (policy.guards.length === 0) {
    return <span class={styles.none}>none</span>;
  }
  return (
    <ol class={styles.guards}>
      {policy.guards.map((guard, index) => (
        <li key={index}>
          <span class={styles.mono}>{guard.type}</span>{' '}
          <Badge kind={guard.mode === 'monitor' ? 'monitor' : 'enforce'} />{' '}
          <GuardSwitch config={config} policy={policy.name} index={index} onChanged={onChanged} />
          {settings(guard) && <span class={styles.settings}> {settings(guard)}</span>}
        </li>
      ))}
    </ol>
  );
}

function Policies({ config, change, onChanged }: SectionProps & { onChanged: () => void }) {
  const columns: Column<PolicyView>[] = [
    { key: 'name', header: 'Policy', cell: (policy) => <Name kind="policy" name={policy.name} /> },
    {
      key: 'guards',
      header: 'Guards, in order',
      cell: (policy) => <Guards policy={policy} config={config} onChanged={onChanged} />,
    },
    {
      key: 'features',
      header: 'Features',
      cell: (policy) => describe(policy.features),
      priority: 2,
    },
    { key: 'redact', header: 'Redact', cell: (policy) => describe(policy.redact), priority: 2 },
    { key: 'refuse', header: 'Refuse', cell: (policy) => describe(policy.refuse), priority: 3 },
  ];
  return (
    <Section title="Policies" name="policies" config={config} change={change}>
      <DataTable
        label="Policies"
        columns={columns}
        rows={config.policies}
        rowKey={(policy) => policy.name}
      />
    </Section>
  );
}

function Providers({ config, change }: SectionProps) {
  const columns: Column<ConfigView['providers'][number]>[] = [
    {
      key: 'name',
      header: 'Provider',
      cell: (provider) => <Name kind="provider" name={provider.name} />,
    },
    { key: 'format', header: 'Format', cell: (provider) => provider.format },
    { key: 'host', header: 'Host', cell: (provider) => provider.host },
    { key: 'region', header: 'Region', cell: (provider) => provider.region ?? '-', priority: 2 },
    {
      key: 'concurrent',
      header: 'Concurrency',
      cell: (provider) => provider.concurrent ?? '-',
      priority: 2,
    },
    {
      key: 'apiKey',
      header: 'API key from',
      cell: (provider) => provider.apiKey ?? '-',
      priority: 3,
    },
  ];
  return (
    <Section title="Providers" name="providers" config={config} change={change}>
      <DataTable
        label="Providers"
        columns={columns}
        rows={config.providers}
        rowKey={(provider) => provider.name}
      />
    </Section>
  );
}

function Models({ config, change }: SectionProps) {
  const columns: Column<ConfigView['models'][number]>[] = [
    { key: 'name', header: 'Model', cell: (model) => model.name },
    {
      key: 'provider',
      header: 'Provider',
      cell: (model) => <Jump kind="provider" name={model.provider} />,
    },
    { key: 'upstream', header: 'Upstream', cell: (model) => model.upstream, priority: 2 },
    {
      key: 'price',
      header: 'Price',
      cell: (model) => (model.free ? 'free' : 'priced'),
      priority: 2,
    },
    { key: 'cap', header: 'Output cap', cell: (model) => model.outputCap ?? '-', priority: 3 },
    {
      key: 'fallback',
      header: 'Fallback',
      cell: (model) => <List items={model.fallback} />,
      priority: 3,
    },
  ];
  return (
    <Section title="Models" name="models" config={config} change={change}>
      <DataTable
        label="Models"
        columns={columns}
        rows={config.models}
        rowKey={(model) => model.name}
      />
    </Section>
  );
}

function DataClasses({ config, change }: SectionProps) {
  const columns: Column<{ name: string; position: number }>[] = [
    { key: 'position', header: 'Order', cell: (item) => item.position },
    { key: 'name', header: 'Data class', cell: (item) => item.name },
  ];
  const rows = config.dataClasses.map((name, index) => ({ name, position: index + 1 }));
  return (
    <Section title="Data classes" name="dataClasses" config={config} change={change}>
      <DataTable
        label="Data classes"
        columns={columns}
        rows={rows}
        rowKey={(item) => item.name}
        empty={
          <p class={styles.empty}>No data classes. Least to most strict, when there are some.</p>
        }
      />
    </Section>
  );
}

function Loading() {
  return (
    <div class={styles.loading} aria-busy="true">
      <Skeleton height="24px" width="40%" />
      <Skeleton height="120px" />
      <Skeleton height="24px" width="40%" />
      <Skeleton height="120px" />
    </div>
  );
}

export function Config() {
  const { config, error, round, retry } = useConfig();
  const [snippet, setSnippet] = useState<Snippet | null>(null);
  const [budgets, setBudgets] = useState<BudgetState[]>([]);
  const reloading = useReload(retry);

  const managed = config?.edits === 'managed';

  useEffect(() => {
    if (!managed) {
      return;
    }
    let stopped = false;
    get<BudgetState[]>('/budgets')
      .then((list) => {
        if (!stopped) {
          setBudgets(list);
        }
      })
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, [managed, round]);

  return (
    <div class={styles.page}>
      <PageHeader title="Config">
        {config && <Hash value={config.version} />}
        <button
          type="button"
          class={styles.button}
          disabled={reloading.busy}
          onClick={reloading.reload}
        >
          Reload
        </button>
      </PageHeader>
      {config && (
        <p class={styles.mode}>
          <span class={styles.modeLabel}>{managed ? 'Edits: on' : 'Edits: off'}</span>{' '}
          {managed ? onText : offText}
        </p>
      )}
      {reloading.problem && <Banner kind="error">{reloading.problem}</Banner>}
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
        <>
          <Tenants config={config} change={setSnippet} budgets={budgets} onChanged={retry} />
          <Keys config={config} change={setSnippet} onChanged={retry} />
          <Policies config={config} change={setSnippet} onChanged={retry} />
          <Providers config={config} change={setSnippet} />
          <Models config={config} change={setSnippet} />
          <DataClasses config={config} change={setSnippet} />
        </>
      )}
      {snippet && (
        <HowToChange snippet={snippet} onClose={() => setSnippet(null)} onChanged={retry} />
      )}
    </div>
  );
}
