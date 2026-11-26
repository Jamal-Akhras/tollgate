import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  budgetsBusyFixture,
  configBusyFixture,
  liveBusyFixture,
  liveFixture,
} from '../../api/fixtures';
import type { LiveSnapshot } from '../../api/types';
import { answer, at } from '../../testing';
import { Metrics } from './Metrics';
import { flow, idle, snapshot } from './snapshots';

const send = vi.fn();
const draw = vi.hoisted(() => vi.fn());

vi.mock('../../ui/plot', () => ({ drawPlot: draw }));

class Observer {
  observe() {}
  disconnect() {}
}

function serve(live: () => Response | Promise<Response>) {
  send.mockImplementation((path: string) => {
    if (path.includes('/live')) {
      return Promise.resolve(live());
    }
    if (path.includes('/budgets')) {
      return Promise.resolve(answer(200, budgetsBusyFixture));
    }
    return Promise.resolve(answer(200, configBusyFixture));
  });
}

async function settle() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

async function show(hash: string, live: () => Response | Promise<Response>) {
  at(hash);
  serve(live);
  render(<Metrics />);
  await settle();
}

function liveCalls(): string[] {
  return send.mock.calls.map((call) => call[0] as string).filter((path) => path.includes('/live'));
}

function rows(table: HTMLElement): string[][] {
  return within(table)
    .getAllByRole('row')
    .slice(1)
    .map((row) => within(row).getAllByRole('cell').map((cell) => cell.textContent ?? ''));
}

function headers(table: HTMLElement): string[] {
  return within(table)
    .getAllByRole('columnheader')
    .map((cell) => cell.textContent ?? '');
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

test('asksForFifteenMinutesInOneMinuteBucketsByDefaultAndLinksToUsage', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  expect(liveCalls()[0]).toBe('/admin/v1/live?minutes=15&step=60');
  expect(screen.getByText(/Since this instance started, at most one hour/)).toBeTruthy();
  const links = screen.getAllByRole('link', { name: 'Usage' });
  expect(links.map((link) => link.getAttribute('href'))).toEqual(['#/usage', '#/usage']);
  expect(screen.getByText('Live')).toBeTruthy();
  expect(draw).toHaveBeenCalledTimes(1);
});

test('asksForTheWindowAndItsStepFromTheUrlAndIgnoresOthers', async () => {
  await show('#/metrics?minutes=5', () => answer(200, liveBusyFixture));
  expect(liveCalls()[0]).toBe('/admin/v1/live?minutes=5&step=15');
  cleanup();
  send.mockClear();
  await show('#/metrics?minutes=60', () => answer(200, liveBusyFixture));
  expect(liveCalls()[0]).toBe('/admin/v1/live?minutes=60&step=60');
  cleanup();
  send.mockClear();
  await show('#/metrics?minutes=7', () => answer(200, liveBusyFixture));
  expect(liveCalls()[0]).toBe('/admin/v1/live?minutes=15&step=60');
});

test('changingTheWindowKeepsTheDataOnScreenWritesTheUrlAndAsksForTheNewWindow', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  serve(() => new Promise<Response>(() => undefined) as unknown as Response);
  fireEvent.click(screen.getByRole('tab', { name: '5 min' }));
  expect(location.hash).toBe('#/metrics?minutes=5');
  await vi.waitFor(() => expect(liveCalls().at(-1)).toBe('/admin/v1/live?minutes=5&step=15'));
  await settle();
  expect(screen.getByRole('tab', { name: '5 min' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.getByText('Latency p95')).toBeTruthy();
  expect(screen.getByRole('region', { name: 'Over time' })).toBeTruthy();
  expect(document.querySelector('[aria-busy="true"]')).toBeNull();
  expect(screen.getByText('926')).toBeTruthy();
});

test('showsTheNewWindowsFiguresOnceTheyArrive', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const smaller: LiveSnapshot = {
    ...liveBusyFixture,
    totals: { ...liveBusyFixture.totals, sent: 40, refused: 0, failed: 0, seconds: 300 },
  };
  serve(() => answer(200, smaller));
  fireEvent.click(screen.getByRole('tab', { name: '5 min' }));
  await vi.waitFor(() => expect(screen.getByText('8 per minute')).toBeTruthy());
  expect(screen.queryByText('926')).toBeNull();
});

test('showsSkeletonsWhileTheFirstAnswerIsOnItsWay', async () => {
  await show('#/metrics', () => new Promise<Response>(() => undefined) as unknown as Response);
  expect(document.querySelector('[aria-busy="true"]')).not.toBeNull();
  expect(screen.queryByText('Live')).toBeNull();
});

test('switchesTheChartBetweenCallsSpendTokensAndLatencyWithItsOwnDataTable', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const chart = screen.getByRole('region', { name: 'Over time' });
  expect(within(chart).getByText('Answered')).toBeTruthy();
  expect(within(chart).getByText('Client errors')).toBeTruthy();
  expect(within(chart).getByText('calls per minute, last 15 minutes')).toBeTruthy();
  expect(draw.mock.calls.at(-1)?.[2]).toMatchObject({ bars: true, stacked: true });
  fireEvent.click(within(chart).getByRole('button', { name: 'Show data' }));
  const calls = screen.getByRole('table', { name: 'Calls, last 15 minutes' });
  expect(headers(calls)).toEqual([
    'Time (UTC)',
    'Answered (calls per minute)',
    'Client errors (calls per minute)',
    'Refused (calls per minute)',
    'Failed (calls per minute)',
  ]);
  expect(within(calls).getAllByRole('row')).toHaveLength(180);

  fireEvent.click(within(chart).getByRole('tab', { name: 'Spend' }));
  await settle();
  expect(headers(screen.getByRole('table', { name: 'Spend, last 15 minutes' }))).toEqual([
    'Time (UTC)',
    'USD (USD per minute)',
  ]);
  expect(draw.mock.calls.at(-1)?.[2]).toMatchObject({ bars: true, stacked: false });

  fireEvent.click(within(chart).getByRole('tab', { name: 'Tokens' }));
  await settle();
  expect(headers(screen.getByRole('table', { name: 'Tokens, last 15 minutes' }))).toEqual([
    'Time (UTC)',
    'Input (tokens per minute)',
    'Output (tokens per minute)',
  ]);
  expect(draw.mock.calls.at(-1)?.[2]).toMatchObject({ bars: true, stacked: true });

  fireEvent.click(within(chart).getByRole('tab', { name: 'Latency' }));
  await settle();
  expect(headers(screen.getByRole('table', { name: 'Latency, last 15 minutes' }))).toEqual([
    'Time (UTC)',
    'p50 (ms)',
    'p95 (ms)',
  ]);
  expect(draw.mock.calls.at(-1)?.[2]).toMatchObject({ bars: false, stacked: false });
});

test('showsAGapAsADashInTheLatencyTable', async () => {
  const live = snapshot({
    ...liveBusyFixture,
    buckets: [
      { ...(liveBusyFixture.buckets[0] as object), sent: 1, p50Millis: null, p95Millis: null },
      { ...(liveBusyFixture.buckets[1] as object) },
    ] as LiveSnapshot['buckets'],
  });
  await show('#/metrics', () => answer(200, live));
  const chart = screen.getByRole('region', { name: 'Over time' });
  fireEvent.click(within(chart).getByRole('tab', { name: 'Latency' }));
  fireEvent.click(within(chart).getByRole('button', { name: 'Show data' }));
  const table = screen.getByRole('table', { name: 'Latency, last 15 minutes' });
  expect(rows(table).map((row) => row.slice(1))).toEqual([['–', '–']]);
});

test('listsEachProviderWithItsStatusFailuresLatencyFallbacksAndCallsInFlight', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const table = screen.getByRole('table', { name: 'Providers' });
  expect(headers(table)).toEqual([
    'Provider',
    'Status',
    'Calls',
    'Failed',
    'p95',
    'Fallbacks to it',
    'In flight now',
  ]);
  expect(rows(table)).toEqual([
    ['openai', 'OK', '564', '1.4%', '1.4 s', '0', '3'],
    ['ollama', 'OK', '226', '0%', '1 s', '11', '1'],
    ['anthropic', 'Degraded', '72', '5.6%', '2 s', '0', '0'],
  ]);
});

test('listsConfiguredProvidersThatHadNoCallsAsIdle', async () => {
  await show('#/metrics', () => answer(200, idle()));
  const table = screen.getByRole('table', { name: 'Providers' });
  expect(rows(table).map((row) => [row[0], row[1]])).toEqual([
    ['anthropic', 'Idle'],
    ['ollama', 'Idle'],
    ['openai', 'Idle'],
  ]);
});

test('listsRefusalReasonsMostFirstAndLinksThemToRefusedCallsInActivity', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const region = screen.getByRole('region', { name: 'Why calls were stopped' });
  const table = within(region).getByRole('table', { name: 'Refused, by reason' });
  expect(rows(table)).toEqual([
    ['data_class', '56'],
    ['body_too_large', '5'],
    ['tool_not_allowed', '3'],
    ['Rate limited, since the start', '2'],
  ]);
  const links = within(table).getAllByRole('link');
  expect(links).toHaveLength(3);
  expect(links.every((link) => link.getAttribute('href') === '#/activity?result=refused')).toBe(
    true,
  );
  expect(within(region).getByText('33 values redacted')).toBeTruthy();
});

test('labelsTheKnownRefusalCodesAndLinksTheirRows', async () => {
  const flows = [flow({ refused: { policy_denied: 3, budget_exceeded: 5 } })];
  await show('#/metrics', () => answer(200, snapshot({ ...liveFixture, flows })));
  const table = screen.getByRole('table', { name: 'Refused, by reason' });
  expect(rows(table)).toEqual([
    ['Over budget', '5'],
    ['Refused by a guard', '3'],
  ]);
});

test('listsGuardsAndLinksEachToItsRefusalsInActivity', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const table = screen.getByRole('table', { name: 'By guard' });
  expect(headers(table)).toEqual(['Guard', 'Allowed', 'Refused', 'Would have refused (monitor)']);
  expect(rows(table)).toEqual([
    ['data-class', '849', '56', '0'],
    ['request-size', '60', '5', '0'],
    ['tool-names', '789', '0', '3'],
  ]);
  expect(
    within(table)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href')),
  ).toEqual(['#/activity?guard=data-class', '#/activity?guard=request-size', '#/activity?guard=tool-names']);
});

test('saysNothingInThisWindowForTablesWithNoRowsAndAnEmptyChartForNoCalls', async () => {
  await show('#/metrics', () => answer(200, idle()));
  expect(screen.getAllByText('Nothing in this window.')).toHaveLength(3);
  expect(screen.getByText('No calls in this window.')).toBeTruthy();
  expect(draw).not.toHaveBeenCalled();
  expect(screen.queryByText(/values redacted/)).toBeNull();
});

test('showsTenantsLinkedToFlowThenModelsAndRestSummedAsOther', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const region = screen.getByRole('region', { name: 'Who is driving it' });
  const tenants = within(region).getByRole('table', { name: 'Calls by tenant' });
  expect(headers(tenants)).toEqual([
    'Tenant',
    'Calls',
    'Failed',
    'Refused',
    'Tokens in / out',
    'Spend',
    'p95',
  ]);
  expect(rows(tenants)).toEqual([
    ['support-bot', '825', '1.5%', '39', '502k / 196.8k', '$0.23', '1.4 s'],
    ['research', '85', '0%', '25', '80k / 30k', '$0', '1 s'],
    ['my-app', '16', '0%', '0', '19.2k / 8,830', '$0', '–'],
  ]);
  expect(
    within(tenants)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href')),
  ).toEqual(['#/flow?tenant=support-bot', '#/flow?tenant=research', '#/flow?tenant=my-app']);

  fireEvent.click(within(region).getByRole('tab', { name: 'Model' }));
  const models = within(region).getByRole('table', { name: 'Calls by model' });
  expect(rows(models).map((row) => row[0])).toEqual(['gpt-fast', 'qwen3', 'claude-sonnet']);
  expect(within(models).queryAllByRole('link')).toHaveLength(0);
});

test('sumsTenantsBeyondTheTopTenAsOther', async () => {
  const flows = Array.from({ length: 12 }, (_, index) =>
    flow({ tenant: `t${String(index).padStart(2, '0')}`, sent: 100 - index }),
  );
  await show('#/metrics', () => answer(200, snapshot({ ...liveFixture, flows })));
  const tenants = screen.getByRole('table', { name: 'Calls by tenant' });
  const last = rows(tenants).at(-1);
  expect(last?.[0]).toBe('Other');
  expect(last?.[1]).toBe(String(90 + 89));
  expect(within(tenants).getAllByRole('link')).toHaveLength(10);
});

test('showsTheBusyFixturesBudgetWithItsBurnAndWhenItReachesTheLimit', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const budgets = screen.getByRole('region', { name: 'Budgets' });
  expect(within(budgets).getByText(/^support-bot · monthly/)).toBeTruthy();
  expect(within(budgets).getByText(/\$0\.93 an hour at this pace/)).toBeTruthy();
  expect(within(budgets).getByText('Reaches the limit in about 5 days at this pace')).toBeTruthy();
});

test('saysSoWhenNoBudgetsAreSet', async () => {
  at('#/metrics');
  send.mockImplementation((path: string) =>
    Promise.resolve(
      path.includes('/live')
        ? answer(200, liveBusyFixture)
        : path.includes('/budgets')
          ? answer(200, [])
          : answer(200, configBusyFixture),
    ),
  );
  render(<Metrics />);
  await settle();
  expect(screen.getByText('No budgets are set.')).toBeTruthy();
});

test('endsWithAFooterLineThatLinksToUsage', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  expect(screen.getByText(/Prometheus metrics are on the management port/)).toBeTruthy();
});

test('saysSoWhenTheLiveViewSkippedRecords', async () => {
  await show('#/metrics', () => answer(200, { ...liveBusyFixture, skipped: true }));
  expect(screen.getByText('Busy: the live view skipped some records')).toBeTruthy();
  expect(screen.getByText('Latency p95')).toBeTruthy();
});

test('showsOnlyTheBannerWhenTheAuditTrailIsOff', async () => {
  await show('#/metrics', () => answer(409, { code: 'audit_off', message: 'off' }));
  expect(screen.getByText(/audit trail is off in tollgate.yaml/)).toBeTruthy();
  expect(screen.queryByText('Latency p95')).toBeNull();
  expect(screen.queryByRole('region', { name: 'Over time' })).toBeNull();
  expect(screen.queryByText(/Prometheus metrics/)).toBeNull();
  expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
});

test('showsTheServersMessageWithARetryWhenThereIsNothingToShow', async () => {
  await show('#/metrics', () => answer(500, { code: 'internal', message: 'The live view failed' }));
  expect(screen.getByRole('alert').textContent).toContain('The live view failed');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
  expect(screen.queryByText('Latency p95')).toBeNull();
});

test('keepsTheLastDataBelowTheRetryBannerWhenARefreshFails', async () => {
  let calls = 0;
  await show('#/metrics', () => {
    calls += 1;
    return calls === 1
      ? answer(200, liveBusyFixture)
      : answer(500, { code: 'internal', message: 'The live view failed' });
  });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(screen.getByRole('alert').textContent).toContain('The live view failed');
  expect(screen.getByText('Latency p95')).toBeTruthy();
  expect(screen.getByText('926')).toBeTruthy();
  expect(screen.queryByText('Live')).toBeNull();
});

test('givesTheRateLimitedRowNoBarBecauseItCoversAnotherSpan', async () => {
  await show('#/metrics', () => answer(200, liveBusyFixture));
  const table = screen.getByRole('table', { name: 'Refused, by reason' });
  const bars = within(table).getAllByRole('row').map((row) => row.querySelectorAll('[aria-hidden]'));
  expect(bars.slice(1).map((found) => found.length)).toEqual([1, 1, 1, 0]);
});

test('showsTheRetryBannerWhenTheBudgetsFailToLoad', async () => {
  at('#/metrics');
  send.mockImplementation((path: string) =>
    Promise.resolve(
      path.includes('/live')
        ? answer(200, liveBusyFixture)
        : path.includes('/budgets')
          ? answer(500, { code: 'internal', message: 'Budgets failed' })
          : answer(200, configBusyFixture),
    ),
  );
  render(<Metrics />);
  await settle();
  const budgets = screen.getByRole('region', { name: 'Budgets' });
  expect(within(budgets).getByRole('alert').textContent).toContain('Budgets failed');
  expect(within(budgets).getByRole('button', { name: 'Retry' })).toBeTruthy();
});
