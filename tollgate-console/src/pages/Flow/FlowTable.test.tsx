import { cleanup, render, screen, within } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { configBusyFixture, liveBusyFixture } from '../../api/fixtures';
import { checksFor, providersFor } from '../../flow/describe';
import { FlowTable } from './FlowTable';

afterEach(cleanup);

function table(live: typeof liveBusyFixture | null = liveBusyFixture) {
  render(
    <FlowTable
      tenants={['support-bot', 'research', 'my-app']}
      selected="research"
      live={live}
      checks={checksFor(configBusyFixture, 'research', [], live)}
      providers={providersFor(configBusyFixture, 'research', live)}
      onSelect={() => undefined}
    />,
  );
}

test('theCallsTableHasARowForEachFlow', () => {
  table();
  const calls = screen.getByRole('table', { name: 'Calls by tenant, model and provider' });
  expect(within(calls).getAllByRole('row')).toHaveLength(liveBusyFixture.flows.length + 1);
  const gpt = within(calls).getByText('gpt-fast').closest('tr')!;
  expect(gpt.textContent).toContain('support-bot');
  expect(gpt.textContent).toContain('$0.0936');
});

test('theChecksTableListsTheSelectedTenantsChecksWithTheirCounts', () => {
  table();
  const checks = screen.getByRole('table', { name: 'Checks for research' });
  const size = within(checks).getByText('request-size').closest('tr')!;
  expect(size.textContent).toContain('64 KB');
  expect(size.textContent).toContain('38');
  expect(size.textContent).toContain('2');
  expect(within(checks).getByText('Signed audit trail')).toBeTruthy();
});

test('withoutALiveViewTheCallsTableSaysThereAreNone', () => {
  table(null);
  expect(screen.getByText('No calls in the last 15 minutes.')).toBeTruthy();
  expect(screen.getByRole('table', { name: 'Checks for research' })).toBeTruthy();
});
