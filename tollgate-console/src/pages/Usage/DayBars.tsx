import { useState } from 'preact/hooks';
import type { Row } from '../../api/types';
import { DataTable, type Column } from '../../ui/DataTable';
import { formatCount } from '../../ui/format';
import styles from './DayBars.module.css';

const slot = 14;
const gap = 3;
const height = 100;

const columns: Column<Row>[] = [
  { key: 'day', header: 'Day', cell: (row) => row.key },
  { key: 'calls', header: 'Calls', align: 'right', cell: (row) => formatCount(row.calls) },
];

export function DayBars({ rows }: { rows: Row[] }) {
  const [shown, setShown] = useState(false);
  const most = Math.max(1, ...rows.map((row) => row.calls));
  return (
    <div class={styles.wrap}>
      <svg
        class={styles.chart}
        viewBox={`0 0 ${rows.length * slot} ${height}`}
        preserveAspectRatio="none"
        role="img"
        aria-label="Calls per day"
      >
        {rows.map((row, index) => {
          const bar = Math.max(row.calls > 0 ? 1 : 0, (row.calls / most) * height);
          return (
            <rect
              key={row.key}
              class={styles.bar}
              x={index * slot}
              y={height - bar}
              width={slot - gap}
              height={bar}
            >
              <title>{`${row.key}: ${formatCount(row.calls)} calls`}</title>
            </rect>
          );
        })}
      </svg>
      <button
        type="button"
        class={styles.toggle}
        aria-expanded={shown}
        onClick={() => setShown(!shown)}
      >
        {shown ? 'Hide data' : 'Show data'}
      </button>
      {shown && (
        <DataTable label="Calls per day" columns={columns} rows={rows} rowKey={(r) => r.key} />
      )}
    </div>
  );
}
