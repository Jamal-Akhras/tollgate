import type { Flow, GuardCount, LiveSnapshot } from '../../api/types';
import { callsOf, type Check, type ProviderNode } from '../../flow/describe';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount } from '../../ui/format';
import { moneyText } from '../../ui/Money';
import styles from './FlowTable.module.css';

type FlowTableProps = {
  tenants: string[];
  selected: string;
  live: LiveSnapshot | null;
  checks: Check[];
  providers: ProviderNode[];
  onSelect: (tenant: string) => void;
};

function refused(flow: Flow): number {
  return Object.values(flow.refused).reduce((sum, count) => sum + count, 0);
}

function spend(flow: Flow): string {
  const parts = Object.entries(flow.cost).map(([currency, amount]) =>
    moneyText({ amount, currency, free: true }),
  );
  return parts.length === 0 ? 'free' : parts.join(' + ');
}

const flowColumns: Column<Flow>[] = [
  { key: 'tenant', header: 'Tenant', cell: (flow) => flow.tenant },
  { key: 'model', header: 'Model', cell: (flow) => flow.model },
  { key: 'provider', header: 'Provider', cell: (flow) => flow.provider },
  { key: 'calls', header: 'Calls', align: 'right', cell: (flow) => formatCount(callsOf([flow])) },
  { key: 'refused', header: 'Refused', align: 'right', cell: (flow) => formatCount(refused(flow)) },
  {
    key: 'fallbacks',
    header: 'Fallbacks',
    align: 'right',
    priority: 2,
    cell: (flow) => formatCount(flow.fallbacks),
  },
  {
    key: 'tokens',
    header: 'Tokens',
    align: 'right',
    priority: 2,
    cell: (flow) => formatCount(flow.inputTokens + flow.outputTokens),
  },
  { key: 'spend', header: 'Spend', align: 'right', cell: spend },
];

const providerColumns: Column<ProviderNode>[] = [
  { key: 'name', header: 'Provider', cell: (provider) => provider.name },
  { key: 'caption', header: 'Region and calls', cell: (provider) => provider.caption },
];

export function FlowTable({
  tenants,
  selected,
  live,
  checks,
  providers,
  onSelect,
}: FlowTableProps) {
  const guards: GuardCount[] = live?.guards.filter((guard) => guard.tenant === selected) ?? [];
  const counts = (check: Check) => guards.find((guard) => guard.guard === check.guard);
  const checkColumns: Column<Check>[] = [
    { key: 'label', header: 'Check', cell: (check) => check.label },
    { key: 'detail', header: 'Detail', cell: (check) => check.detail },
    {
      key: 'mode',
      header: 'Mode',
      priority: 2,
      cell: (check) =>
        check.state === 'monitor' ? 'monitor' : check.state === 'off' ? 'off' : 'enforce',
    },
    {
      key: 'allowed',
      header: 'Allowed',
      align: 'right',
      priority: 2,
      cell: (check) => formatCount(counts(check)?.allowed ?? 0),
    },
    {
      key: 'refused',
      header: 'Refused',
      align: 'right',
      priority: 2,
      cell: (check) => formatCount(counts(check)?.refused ?? 0),
    },
    {
      key: 'monitored',
      header: 'Would have refused',
      align: 'right',
      priority: 3,
      cell: (check) => formatCount(counts(check)?.monitored ?? 0),
    },
  ];

  return (
    <div class={styles.tables}>
      <h2 class={styles.heading}>Calls in the last 15 minutes</h2>
      <DataTable
        label="Calls by tenant, model and provider"
        columns={flowColumns}
        rows={live?.flows ?? []}
        rowKey={(flow) => `${flow.tenant}/${flow.model}/${flow.provider}`}
        empty="No calls in the last 15 minutes."
      />
      <label class={styles.pick}>
        Tenant
        <select value={selected} onChange={(event) => onSelect(event.currentTarget.value)}>
          {tenants.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>
      <h2 class={styles.heading}>Checks, in the order requests meet them</h2>
      <DataTable
        label={`Checks for ${selected}`}
        columns={checkColumns}
        rows={checks}
        rowKey={(check) => check.id}
      />
      <h2 class={styles.heading}>Providers</h2>
      <DataTable
        label={`Providers for ${selected}`}
        columns={providerColumns}
        rows={providers}
        rowKey={(provider) => provider.name}
      />
    </div>
  );
}
