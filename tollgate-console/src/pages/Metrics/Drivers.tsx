import { useState } from 'preact/hooks';
import type { LiveSnapshot } from '../../api/types';
import { href } from '../../router';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount, formatMillis } from '../../ui/format';
import { Segmented } from '../../ui/Segmented';
import { driverRows, type By, type DriverRow } from './grouping';
import { percentText, spendText } from './figures';
import styles from './Metrics.module.css';

const options: { value: By; label: string }[] = [
  { value: 'tenant', label: 'Tenant' },
  { value: 'model', label: 'Model' },
];

function columnsFor(by: By): Column<DriverRow>[] {
  return [
    {
      key: 'name',
      header: by === 'tenant' ? 'Tenant' : 'Model',
      cell: (row) =>
        by === 'tenant' && !row.other ? (
          <a href={href('flow', { tenant: row.name })}>{row.name}</a>
        ) : (
          row.name
        ),
    },
    { key: 'calls', header: 'Calls', align: 'right', cell: (row) => formatCount(row.calls) },
    {
      key: 'failed',
      header: 'Failed',
      align: 'right',
      cell: (row) => percentText(row.failedShare),
    },
    { key: 'refused', header: 'Refused', align: 'right', cell: (row) => formatCount(row.refused) },
    {
      key: 'tokens',
      header: 'Tokens in / out',
      align: 'right',
      priority: 2,
      cell: (row) => `${formatCount(row.inputTokens)} / ${formatCount(row.outputTokens)}`,
    },
    { key: 'spend', header: 'Spend', align: 'right', cell: (row) => spendText(row.cost) },
    {
      key: 'p95',
      header: 'p95',
      align: 'right',
      cell: (row) => (row.p95Millis === null ? '–' : formatMillis(row.p95Millis)),
    },
  ];
}

export function Drivers({ live }: { live: LiveSnapshot }) {
  const [by, setBy] = useState<By>('tenant');
  return (
    <section class={styles.section} aria-label="Who is driving it">
      <div class={styles.sectionHead}>
        <h2 class={styles.heading}>Who is driving it</h2>
        <Segmented label="Group by" options={options} value={by} onChange={setBy} />
      </div>
      <DataTable
        label={`Calls by ${by}`}
        columns={columnsFor(by)}
        rows={driverRows(live, by)}
        rowKey={(row) => row.name}
        empty="Nothing in this window."
      />
    </section>
  );
}
