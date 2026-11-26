import type { LiveSnapshot } from '../../api/types';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount, formatMillis } from '../../ui/format';
import { percentText } from './figures';
import styles from './Metrics.module.css';
import { providerRows, statusText, type ProviderRow } from './health';

const columns: Column<ProviderRow>[] = [
  { key: 'name', header: 'Provider', cell: (row) => row.name },
  {
    key: 'status',
    header: 'Status',
    cell: (row) => (
      <span class={styles.status}>
        <span class={`${styles.mark} ${styles[row.status]}`} aria-hidden="true" />
        {statusText[row.status]}
      </span>
    ),
  },
  { key: 'calls', header: 'Calls', align: 'right', cell: (row) => formatCount(row.calls) },
  { key: 'failed', header: 'Failed', align: 'right', cell: (row) => percentText(row.failedShare) },
  {
    key: 'p95',
    header: 'p95',
    align: 'right',
    cell: (row) => (row.p95Millis === null ? '–' : formatMillis(row.p95Millis)),
  },
  {
    key: 'fallbacks',
    header: 'Fallbacks to it',
    align: 'right',
    priority: 2,
    cell: (row) => formatCount(row.fallbacks),
  },
  {
    key: 'inFlight',
    header: 'In flight now',
    align: 'right',
    priority: 2,
    cell: (row) => formatCount(row.inFlight),
  },
];

type ProvidersProps = { live: LiveSnapshot; configured: string[] };

export function Providers({ live, configured }: ProvidersProps) {
  return (
    <section class={styles.section} aria-label="Providers">
      <h2 class={styles.heading}>Providers</h2>
      <DataTable
        label="Providers"
        columns={columns}
        rows={providerRows(live, configured)}
        rowKey={(row) => row.name}
        empty="Nothing in this window."
      />
    </section>
  );
}
