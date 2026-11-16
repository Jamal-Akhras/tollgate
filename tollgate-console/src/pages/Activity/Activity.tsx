import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import { usePoll } from '../../api/poll';
import type { CallView, ConfigView } from '../../api/types';
import { navigate, route } from '../../router';
import { Badge } from '../../ui/Badge';
import { Banner } from '../../ui/Banner';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount, formatMillis } from '../../ui/format';
import { Money } from '../../ui/Money';
import { PageHeader } from '../../ui/PageHeader';
import { Skeleton } from '../../ui/Skeleton';
import { Time } from '../../ui/Time';
import { FirstRun } from '../Flow/FirstRun';
import styles from './Activity.module.css';
import { CallDrawer } from './CallDrawer';
import {
  endpointName,
  filterCalls,
  filterQuery,
  guardsIn,
  readFilters,
  resultKind,
  resultKinds,
  tenantsIn,
  type Filters,
} from './filters';

const auditOffText = 'The audit trail is off in tollgate.yaml, so there is nothing to show here';

function ResultCell({ call }: { call: CallView }) {
  const kind = resultKind(call);
  const detail = kind === 'refused' ? call.code : kind === 'failed' ? call.outcome?.result : null;
  return (
    <span class={styles.result}>
      <Badge kind={kind} />
      {detail && <span class={styles.detail}>{detail}</span>}
      {call.fallback && <Badge kind="fallback" />}
    </span>
  );
}

function columns(now: number): Column<CallView>[] {
  return [
    { key: 'time', header: 'Time', cell: (call) => <Time iso={call.time} now={now} /> },
    { key: 'tenant', header: 'Tenant', cell: (call) => call.tenant },
    { key: 'model', header: 'Model', cell: (call) => call.model },
    { key: 'provider', header: 'Provider', priority: 2, cell: (call) => call.provider },
    { key: 'result', header: 'Result', cell: (call) => <ResultCell call={call} /> },
    {
      key: 'tokens',
      header: 'Tokens in / out',
      align: 'right',
      priority: 2,
      cell: (call) =>
        call.outcome?.tokens
          ? `${formatCount(call.outcome.tokens.input)} / ${formatCount(call.outcome.tokens.output)}`
          : '—',
    },
    {
      key: 'cost',
      header: 'Cost',
      align: 'right',
      cell: (call) =>
        call.outcome ? (
          <Money amount={call.outcome.cost.amount} currency={call.outcome.cost.currency} />
        ) : (
          '—'
        ),
    },
    {
      key: 'duration',
      header: 'Duration',
      align: 'right',
      priority: 2,
      cell: (call) => (call.outcome ? formatMillis(call.outcome.millis) : '—'),
    },
    { key: 'keyId', header: 'Key', priority: 3, cell: (call) => <code>{call.key}</code> },
    { key: 'dataClass', header: 'Data class', priority: 3, cell: (call) => call.dataClass ?? '—' },
    { key: 'endpoint', header: 'Format', priority: 3, cell: (call) => endpointName(call.endpoint) },
  ];
}

type FilterBarProps = { calls: CallView[]; filters: Filters };

function Select(props: {
  label: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}) {
  return (
    <label class={styles.field}>
      <span class={styles.label}>{props.label}</span>
      <select
        class={styles.select}
        value={props.value}
        onChange={(event) => props.onChange(event.currentTarget.value)}
      >
        <option value="">All</option>
        {props.options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}

function FilterBar({ calls, filters }: FilterBarProps) {
  const change = (name: keyof Filters) => (value: string) =>
    navigate('activity', filterQuery({ ...filters, [name]: value }));
  return (
    <div class={styles.filters} role="group" aria-label="Filters">
      <Select
        label="Tenant"
        value={filters.tenant}
        options={tenantsIn(calls)}
        onChange={change('tenant')}
      />
      <Select label="Result" value={filters.result} options={resultKinds} onChange={change('result')} />
      <Select label="Guard" value={filters.guard} options={guardsIn(calls)} onChange={change('guard')} />
    </div>
  );
}

function Empty() {
  const [config, setConfig] = useState<ConfigView | null>(null);
  useEffect(() => {
    let stopped = false;
    get<ConfigView>('/config')
      .then((view) => !stopped && setConfig(view))
      .catch(() => undefined);
    return () => {
      stopped = true;
    };
  }, []);
  const tenant = config?.tenants[0]?.name;
  return config && tenant ? <FirstRun config={config} tenant={tenant} /> : <Skeleton height="120px" />;
}

function useTopOfPage() {
  const away = useRef(false);
  useEffect(() => {
    const scrolled = (event: Event) => {
      const target = event.target;
      const top = target instanceof Element ? target.scrollTop : window.scrollY;
      away.current = top > 0;
    };
    document.addEventListener('scroll', scrolled, true);
    return () => document.removeEventListener('scroll', scrolled, true);
  }, []);
  return away;
}

export function Activity() {
  const [openId, setOpenId] = useState<string | null>(null);
  const away = useTopOfPage();
  const drawerOpen = useRef(false);
  drawerOpen.current = openId !== null;
  const activity = usePoll(
    () => get<CallView[]>('/activity', { limit: 200 }),
    5000,
    () => drawerOpen.current || away.current,
  );
  const filters = readFilters(route.value.query);

  const error = activity.error.value;
  const auditOff = error instanceof ApiError && error.code === 'audit_off';
  const calls = auditOff ? null : activity.data.value;
  const now = Date.now();
  const shown = calls ? filterCalls(calls, filters) : [];
  const open = calls?.find((call) => call.id === openId);

  return (
    <div class={styles.page}>
      <PageHeader
        title="Activity"
        note="The last 200 calls this instance decided"
        error={auditOff ? null : error}
        updatedAt={activity.updatedAt.value}
        now={now}
      />
      {auditOff && <Banner kind="info">{auditOffText}</Banner>}
      {error && !calls && !auditOff && (
        <Banner
          kind="error"
          action={
            <button type="button" class={styles.button} onClick={activity.refresh}>
              Retry
            </button>
          }
        >
          {error.message}
        </Banner>
      )}
      {!calls && !error && (
        <div class={styles.loading} aria-busy="true">
          <Skeleton height="40px" />
          <Skeleton height="240px" />
        </div>
      )}
      {calls && calls.length === 0 && <Empty />}
      {calls && calls.length > 0 && (
        <>
          <FilterBar calls={calls} filters={filters} />
          <DataTable
            label="Calls"
            columns={columns(now)}
            rows={shown}
            rowKey={(call) => call.id}
            onRowClick={(call) => setOpenId(call.id)}
            empty={shown.length === 0 ? 'No calls match these filters.' : undefined}
          />
        </>
      )}
      {open && <CallDrawer call={open} now={now} onClose={() => setOpenId(null)} />}
    </div>
  );
}
