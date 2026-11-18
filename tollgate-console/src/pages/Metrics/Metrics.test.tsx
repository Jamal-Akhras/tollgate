import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { budgetsBusyFixture, liveBusyFixture } from '../../api/fixtures';
import { Metrics } from './Metrics';
import { answer, at } from '../../testing';

const send = vi.fn();
const draw = vi.hoisted(() => vi.fn());

vi.mock('../../ui/plot', () => ({ drawPlot: draw }));

class Observer {
  observe() {}
  disconnect() {}
}

function serve(live: () => Response) {
  send.mockImplementation((path: string) =>
    Promise.resolve(path.includes('/live') ? live() : answer(200, budgetsBusyFixture)),
  );
}

async function show(hash: string, live: () => Response) {
  at(hash);
  serve(live);
  render(<Metrics />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

beforeEach(() => {
  send.mockReset();
  draw.mockReset();
  draw.mockResolvedValue({ setData: vi.fn(), resize: vi.fn(), destroy: vi.fn() });
  vi.useFakeTimers();
  vi.stubGlobal('fetch', send);
  vi.stubGlobal('ResizeObserver', Observer);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test('asksForTheFifteenMinuteWindowByDefaultAndSaysWhatTheNumbersCover', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/live?minutes=15');
  expect(
    screen.getByText(
      'Since this instance started, at most one hour. For longer periods see Usage.',
    ),
  ).toBeTruthy();
  expect(screen.getByText('calls/s, last 15 minutes')).toBeTruthy();
  expect(screen.getByText('ms, last 15 minutes')).toBeTruthy();
  expect(draw).toHaveBeenCalledTimes(4);
});

test('asksForTheWindowInTheUrlAndIgnoresOthers', async () => {
  await show('#/metrics?minutes=60', () => answer(200, liveBusyFixture));
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/live?minutes=60');
  expect(screen.getByText('tokens/s, last 60 minutes')).toBeTruthy();
  cleanup();
  send.mockClear();
  await show('#/metrics?minutes=7', () => answer(200, liveBusyFixture));
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/live?minutes=15');
});

test('changingTheWindowWritesItToTheUrl', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  fireEvent.click(screen.getByRole('tab', { name: '5 min' }));
  expect(location.hash).toBe('#/metrics?minutes=5');
});

test('showsEachChartsNumbersAsATableOnRequest', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  expect(screen.queryByRole('table')).toBeNull();
  const calls = screen.getByRole('region', { name: 'Calls per second' });
  fireEvent.click(within(calls).getByRole('button', { name: 'Show data' }));
  const table = screen.getByRole('table', { name: 'Calls per second, last 15 minutes' });
  const headers = within(table)
    .getAllByRole('columnheader')
    .map((cell) => cell.textContent);
  expect(headers).toEqual([
    'Time (UTC)',
    'Sent (calls/s)',
    'Refused (calls/s)',
    'Failed (calls/s)',
  ]);
  expect(within(table).getAllByRole('row')).toHaveLength(181);
  const latency = screen.getByRole('region', { name: 'Latency' });
  fireEvent.click(within(latency).getByRole('button', { name: 'Show data' }));
  expect(screen.getByRole('table', { name: 'Latency, last 15 minutes' })).toBeTruthy();
});

test('showsAGapAsADashInTheTable', async () => {
  const live = {
    ...liveBusyFixture,
    buckets: [{ ...(liveBusyFixture.buckets[0] as object), p50Millis: null, p95Millis: null }],
  };
  await show('#/metrics', () => answer(200, live));
  const latency = screen.getByRole('region', { name: 'Latency' });
  fireEvent.click(within(latency).getByRole('button', { name: 'Show data' }));
  const row = within(within(latency).getByRole('table')).getAllByRole('row')[1] as HTMLElement;
  expect(within(row).getAllByText('–')).toHaveLength(2);
});

test('saysSoWhenTheWindowHasNoBuckets', async () => {
  await show('#/metrics', () => answer(200, { ...liveBusyFixture, buckets: [] }));
  expect(screen.getAllByText('No calls in this window.')).toHaveLength(4);
  expect(draw).not.toHaveBeenCalled();
});

test('showsEachTenantsBudgetAndTheCallsInFlightPerProvider', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const budgets = screen.getByRole('region', { name: 'Budgets' });
  const first = budgetsBusyFixture[0];
  expect(within(budgets).getByText(new RegExp(`^${first?.tenant} `))).toBeTruthy();
  expect(within(budgets).getAllByText(/^Reserved /).length).toBe(budgetsBusyFixture.length);
  const flight = screen.getByRole('region', { name: 'Calls in flight' });
  expect(within(flight).getByText('openai').nextSibling?.textContent).toBe('3');
  expect(within(flight).getByText('ollama').nextSibling?.textContent).toBe('1');
});

test('saysSoWhenTheLiveViewSkippedRecords', async () => {
  await show('#/metrics', () => answer(200, { ...liveBusyFixture, skipped: true }));
  expect(screen.getByText('Busy: the live view skipped some records')).toBeTruthy();
});

test('saysSoWhenTheAuditTrailIsOff', async () => {
  await show('#/metrics', () => answer(409, { code: 'audit_off', message: 'off' }));
  expect(screen.getByText(/audit trail is off in tollgate.yaml/)).toBeTruthy();
  expect(screen.queryByRole('region', { name: 'Latency' })).toBeNull();
});

test('showsTheServersMessageWithARetry', async () => {
  await show('#/metrics', () => answer(500, { code: 'internal', message: 'The live view failed' }));
  expect(screen.getByRole('alert').textContent).toContain('The live view failed');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});
