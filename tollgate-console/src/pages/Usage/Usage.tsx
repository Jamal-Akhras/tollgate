import { useEffect, useState } from 'preact/hooks';
import { ApiError, get } from '../../api/client';
import type { Row, UsageReport } from '../../api/types';
import { navigate, route } from '../../router';
import { Banner } from '../../ui/Banner';
import buttons from '../../ui/button.module.css';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount } from '../../ui/format';
import { Hash } from '../../ui/Hash';
import { Money } from '../../ui/Money';
import { PageHeader } from '../../ui/PageHeader';
import { RetryBanner } from '../../ui/RetryBanner';
import { Skeleton } from '../../ui/Skeleton';
import { auditOffText, isAuditOff } from '../auditOff';
import { refusedTotal, toCsv } from './csv';
import { DayBars } from './DayBars';
import styles from './Usage.module.css';

const groupings = ['tenant', 'model', 'key', 'user', 'day'];

function day(time: number): string {
  return new Date(time).toISOString().slice(0, 10);
}

function defaultFrom(now: number): string {
  return `${day(now).slice(0, 8)}01`;
}

function defaultTo(now: number): string {
  return day(now + 24 * 60 * 60 * 1000);
}

function keyCell(by: string, row: Row) {
  if (by === 'user' && row.key !== 'unattributed') {
    return <Hash value={row.key} />;
  }
  return by === 'key' || by === 'user' ? <code>{row.key}</code> : row.key;
}

function columns(by: string): Column<Row>[] {
  return [
    { key: 'key', header: by, cell: (row) => keyCell(by, row) },
    { key: 'calls', header: 'Calls', align: 'right', cell: (row) => formatCount(row.calls) },
    {
      key: 'refused',
      header: 'Refused',
      align: 'right',
      cell: (row) => {
        const codes = Object.entries(row.refused).map(([code, count]) => `${code}: ${count}`);
        return (
          <span title={codes.join(', ') || undefined}>
            {formatCount(refusedTotal(row))}
            {codes.length > 0 && <span class={styles.codes}>{codes.join(', ')}</span>}
          </span>
        );
      },
    },
    {
      key: 'unfinished',
      header: 'Unfinished',
      align: 'right',
      priority: 2,
      cell: (row) => formatCount(row.unfinished),
    },
    {
      key: 'input',
      header: 'Input tokens',
      align: 'right',
      priority: 2,
      cell: (row) => formatCount(row.tokens.input),
    },
    {
      key: 'output',
      header: 'Output tokens',
      align: 'right',
      priority: 2,
      cell: (row) => formatCount(row.tokens.output),
    },
    {
      key: 'cacheRead',
      header: 'Cache read',
      align: 'right',
      priority: 3,
      cell: (row) => formatCount(row.tokens.cacheRead),
    },
    {
      key: 'cacheWrite',
      header: 'Cache write',
      align: 'right',
      priority: 3,
      cell: (row) => formatCount(row.tokens.cacheWrite),
    },
    {
      key: 'cost',
      header: 'Cost',
      align: 'right',
      cell: (row) => {
        const costs = Object.entries(row.cost);
        return costs.length === 0
          ? '—'
          : costs.map(([currency, amount]) => (
              <div key={currency}>
                <Money amount={amount} currency={currency} />
              </div>
            ));
      },
    },
  ];
}

type Loaded = { report: UsageReport | null; error: Error | null };

function useReport(from: string | undefined, to: string | undefined, by: string) {
  const [state, setState] = useState<Loaded>({ report: null, error: null });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let stopped = false;
    setState({ report: null, error: null });
    get<UsageReport>('/usage', { ...(from ? { from } : {}), ...(to ? { to } : {}), by })
      .then((report) => !stopped && setState({ report, error: null }))
      .catch((error: Error) => !stopped && setState({ report: null, error }));
    return () => {
      stopped = true;
    };
  }, [from, to, by, attempt]);
  return { ...state, retry: () => setAttempt(attempt + 1) };
}

function CopyCsv({ report }: { report: UsageReport }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(toCsv(report));
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }
  return (
    <button type="button" class={buttons.button} onClick={copy}>
      {copied ? 'Copied' : 'Copy as CSV'}
    </button>
  );
}

export function Usage() {
  const query = route.value.query;
  const by = groupings.includes(query.by ?? '') ? (query.by as string) : 'tenant';
  const from = query.from || undefined;
  const to = query.to || undefined;
  const now = Date.now();
  const { report, error, retry } = useReport(from, to, by);

  const change = (name: 'from' | 'to' | 'by', value: string) => {
    navigate('usage', {
      from: from ?? defaultFrom(now),
      to: to ?? defaultTo(now),
      by,
      [name]: value,
    });
  };

  const rejected = error instanceof ApiError && error.status === 400;
  const auditOff = isAuditOff(error);
  const failed = error && !rejected && !auditOff;

  return (
    <div class={styles.page}>
      <PageHeader title="Usage" note="Summed from the signed audit trail on each request.">
        {report && <CopyCsv report={report} />}
      </PageHeader>
      <div class={styles.controls} role="group" aria-label="Report">
        <label class={styles.field}>
          <span class={styles.label}>From (UTC)</span>
          <input
            class={styles.input}
            type="date"
            value={from ?? defaultFrom(now)}
            onChange={(event) => change('from', event.currentTarget.value)}
          />
        </label>
        <label class={styles.field}>
          <span class={styles.label}>To, not included (UTC)</span>
          <input
            class={styles.input}
            type="date"
            value={to ?? defaultTo(now)}
            onChange={(event) => change('to', event.currentTarget.value)}
          />
        </label>
        <label class={styles.field}>
          <span class={styles.label}>By</span>
          <select
            class={styles.input}
            value={by}
            onChange={(event) => change('by', event.currentTarget.value)}
          >
            {groupings.map((grouping) => (
              <option key={grouping} value={grouping}>
                {grouping}
              </option>
            ))}
          </select>
        </label>
      </div>
      {rejected && (
        <p class={styles.problem} role="alert">
          {error.message}
        </p>
      )}
      {auditOff && <Banner kind="info">{auditOffText}</Banner>}
      {failed && (
        <RetryBanner error={error} onRetry={retry} />
      )}
      {!report && !error && (
        <div class={styles.loading} aria-busy="true">
          <Skeleton height="240px" />
        </div>
      )}
      {report && (
        <>
          {by === 'user' && (
            <p class={styles.note}>
              Each row is a hashed user ID: the HMAC a user ID is recorded under. To get the hash
              for an ID, run <code>java -jar tollgate.jar audit user tollgate.yaml &lt;id&gt;</code>
              .
            </p>
          )}
          {by === 'day' && report.rows.length > 0 && <DayBars rows={report.rows} />}
          <DataTable
            label="Usage"
            columns={columns(by)}
            rows={report.rows}
            rowKey={(row) => row.key}
            empty="No calls in this period."
          />
        </>
      )}
    </div>
  );
}
