import type { ComponentChildren } from 'preact';
import styles from './DataTable.module.css';

export type Column<Row> = {
  key: string;
  header: string;
  cell: (row: Row) => ComponentChildren;
  align?: 'left' | 'right';
  priority?: 1 | 2 | 3;
};

type DataTableProps<Row> = {
  label: string;
  columns: Column<Row>[];
  rows: Row[];
  rowKey: (row: Row) => string;
  onRowClick?: (row: Row) => void;
  empty?: ComponentChildren;
};

function classes<Row>(column: Column<Row>): string {
  return [
    column.align === 'right' ? styles.right : '',
    styles[`priority${column.priority ?? 1}`],
  ].join(' ');
}

export function DataTable<Row>({
  label,
  columns,
  rows,
  rowKey,
  onRowClick,
  empty,
}: DataTableProps<Row>) {
  if (rows.length === 0 && empty) {
    return <div class={styles.empty}>{empty}</div>;
  }
  return (
    <div class={styles.wrap}>
      <table class={styles.table} aria-label={label}>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key} scope="col" class={classes(column)}>
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr
              key={rowKey(row)}
              class={onRowClick ? styles.clickable : undefined}
              tabIndex={onRowClick ? 0 : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={
                onRowClick
                  ? (event) => {
                      if (event.key === 'Enter' && event.target === event.currentTarget) {
                        onRowClick(row);
                      }
                    }
                  : undefined
              }
            >
              {columns.map((column) => (
                <td key={column.key} class={classes(column)} data-label={column.header}>
                  {column.cell(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
