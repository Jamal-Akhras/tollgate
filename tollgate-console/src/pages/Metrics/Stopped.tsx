import type { LiveSnapshot } from '../../api/types';
import { href } from '../../router';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount } from '../../ui/format';
import styles from './Metrics.module.css';
import { guardRows, reasons, redacted, type GuardRow, type Reason } from './refusals';

const nothing = 'Nothing in this window.';

function reasonColumns(most: number): Column<Reason>[] {
  return [
    {
      key: 'reason',
      header: 'Reason',
      cell: (row) =>
        row.code === null ? (
          row.label
        ) : (
          <a href={href('activity', { result: 'refused' })}>{row.label}</a>
        ),
    },
    {
      key: 'count',
      header: 'Calls',
      align: 'right',
      cell: (row) => (
        <span class={styles.counted}>
          {row.code !== null && (
            <span class={styles.track} aria-hidden="true">
              <span class={styles.fill} style={{ width: `${(row.count / most) * 100}%` }} />
            </span>
          )}
          {formatCount(row.count)}
        </span>
      ),
    },
  ];
}

const guardColumns: Column<GuardRow>[] = [
  {
    key: 'guard',
    header: 'Guard',
    cell: (row) => <a href={href('activity', { guard: row.guard })}>{row.guard}</a>,
  },
  { key: 'allowed', header: 'Allowed', align: 'right', cell: (row) => formatCount(row.allowed) },
  { key: 'refused', header: 'Refused', align: 'right', cell: (row) => formatCount(row.refused) },
  {
    key: 'monitored',
    header: 'Would have refused (monitor)',
    align: 'right',
    cell: (row) => formatCount(row.monitored),
  },
];

export function Stopped({ live }: { live: LiveSnapshot }) {
  const why = reasons(live);
  const most = Math.max(1, ...why.filter((row) => row.code !== null).map((row) => row.count));
  const values = redacted(live);
  return (
    <section class={styles.section} aria-label="Why calls were stopped">
      <h2 class={styles.heading}>Why calls were stopped</h2>
      <div class={styles.pair}>
        <div class={styles.table}>
          <h3 class={styles.subheading}>Refused, by reason</h3>
          <DataTable
            label="Refused, by reason"
            columns={reasonColumns(most)}
            rows={why}
            rowKey={(row) => row.code ?? 'rate_limited'}
            empty={nothing}
          />
        </div>
        <div class={styles.table}>
          <h3 class={styles.subheading}>By guard</h3>
          <DataTable
            label="By guard"
            columns={guardColumns}
            rows={guardRows(live)}
            rowKey={(row) => row.guard}
            empty={nothing}
          />
        </div>
      </div>
      {values > 0 && <p class={styles.muted}>{formatCount(values)} values redacted</p>}
    </section>
  );
}
