import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { DataTable, type Column } from './DataTable';

afterEach(cleanup);

type Row = { id: string; tenant: string; calls: number };

const rows: Row[] = [
  { id: 'a', tenant: 'my-app', calls: 12 },
  { id: 'b', tenant: 'batch-jobs', calls: 3 },
];

const columns: Column<Row>[] = [
  { key: 'tenant', header: 'Tenant', cell: (row) => row.tenant },
  { key: 'calls', header: 'Calls', cell: (row) => row.calls, align: 'right', priority: 2 },
];

test('rendersAHeaderAndARowPerItem', () => {
  render(<DataTable label="Tenants" columns={columns} rows={rows} rowKey={(row) => row.id} />);
  expect(screen.getByRole('table', { name: 'Tenants' })).toBeTruthy();
  expect(screen.getAllByRole('columnheader').map((cell) => cell.textContent)).toEqual([
    'Tenant',
    'Calls',
  ]);
  expect(screen.getAllByRole('row')).toHaveLength(3);
  expect(screen.getByText('batch-jobs')).toBeTruthy();
});

test('rowsAreNotFocusableWithoutAClickHandler', () => {
  render(<DataTable label="Tenants" columns={columns} rows={rows} rowKey={(row) => row.id} />);
  expect(screen.getAllByRole('row')[1]?.getAttribute('tabindex')).toBeNull();
});

test('opensARowWithAClickOrEnter', () => {
  const open = vi.fn();
  render(
    <DataTable
      label="Tenants"
      columns={columns}
      rows={rows}
      rowKey={(row) => row.id}
      onRowClick={open}
    />,
  );
  const row = screen.getAllByRole('row')[1] as HTMLElement;
  expect(row.getAttribute('tabindex')).toBe('0');
  fireEvent.keyDown(row, { key: 'Enter' });
  expect(open).toHaveBeenLastCalledWith(rows[0]);
  fireEvent.keyDown(row, { key: 'a' });
  expect(open).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getAllByRole('row')[2] as HTMLElement);
  expect(open).toHaveBeenLastCalledWith(rows[1]);
});

test('showsTheEmptySlotWhenThereAreNoRows', () => {
  render(
    <DataTable
      label="Tenants"
      columns={columns}
      rows={[]}
      rowKey={(row) => row.id}
      empty={<p>No calls yet</p>}
    />,
  );
  expect(screen.getByText('No calls yet')).toBeTruthy();
  expect(screen.queryByRole('table')).toBeNull();
});
