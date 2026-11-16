import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { PageHeader } from './PageHeader';

afterEach(cleanup);

const now = Date.parse('2026-11-16T12:00:00Z');

test('showsTheTitleTheNoteAndTheControls', () => {
  render(
    <PageHeader title="Usage" note="Spend per tenant in the last 24 hours">
      <button type="button">Reload</button>
    </PageHeader>,
  );
  expect(screen.getByRole('heading', { level: 1, name: 'Usage' })).toBeTruthy();
  expect(screen.getByText('Spend per tenant in the last 24 hours')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Reload' })).toBeTruthy();
});

test('saysHowOldTheDataIsWhenARefreshFails', () => {
  render(
    <PageHeader title="Usage" error={new Error('down')} updatedAt={now - 14_000} now={now} />,
  );
  expect(screen.getByRole('status').textContent).toBe('Last updated 14 s ago. Retrying.');
});

test('saysNothingWhenThereIsNoErrorOrNoData', () => {
  render(<PageHeader title="Usage" error={null} updatedAt={now - 14_000} now={now} />);
  render(<PageHeader title="Audit" error={new Error('down')} updatedAt={null} now={now} />);
  expect(screen.queryByRole('status')).toBeNull();
});
