import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { activityBusyFixture, activityFixture, configFixture } from '../../api/fixtures';
import type { CallView } from '../../api/types';
import { parse, route } from '../../router';
import { Activity } from './Activity';

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function serve(calls: () => Response) {
  send.mockImplementation((path: string) =>
    Promise.resolve(path.includes('/activity') ? calls() : answer(200, configFixture)),
  );
}

function activityCalls() {
  return send.mock.calls.filter(([path]) => String(path).includes('/activity')).length;
}

async function tick(millis: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(millis);
  });
}

function rowOf(id: string): HTMLElement {
  const index = activityBusyFixture.findIndex((item) => item.id === id);
  return screen.getAllByRole('row')[index + 1] as HTMLElement;
}

beforeEach(() => {
  send.mockReset();
  vi.useFakeTimers();
  location.hash = '#/activity';
  route.value = parse('#/activity');
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test('showsSkeletonsThenTheCalls', async () => {
  serve(() => answer(200, activityBusyFixture));
  const { container } = render(<Activity />);
  expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
  await tick(0);
  expect(container.querySelector('[aria-busy="true"]')).toBeNull();
  expect(screen.getByRole('table', { name: 'Calls' })).toBeTruthy();
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/activity?limit=200');
});

test('eachResultHasItsBadge', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  const table = screen.getByRole('table', { name: 'Calls' });
  expect(within(table).getAllByText('sent').length).toBeGreaterThan(0);
  expect(within(table).getByText('data_class')).toBeTruthy();
  expect(within(table).getAllByText('refused').length).toBeGreaterThan(0);
  expect(within(table).getByText('failed')).toBeTruthy();
  expect(within(table).getByText('upstream_error')).toBeTruthy();
  expect(within(table).getByText('fallback')).toBeTruthy();
});

test('showsTheFormatByName', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  expect(screen.getAllByText('OpenAI').length).toBeGreaterThan(0);
  expect(screen.getAllByText('Anthropic').length).toBe(1);
});

test('aWaitingAndADryRunCallHaveTheirBadges', async () => {
  const base = activityFixture[0] as CallView;
  const waiting = { ...base, id: 'w', outcome: null };
  const dry = {
    ...base,
    id: 'd',
    outcome: { ...(base.outcome as NonNullable<CallView['outcome']>), result: 'dry_run' },
  };
  serve(() => answer(200, [waiting, dry]));
  render(<Activity />);
  await tick(0);
  const table = screen.getByRole('table', { name: 'Calls' });
  expect(within(table).getByText('waiting')).toBeTruthy();
  expect(within(table).getByText('dry run')).toBeTruthy();
});

test('readsTheFiltersFromTheUrl', async () => {
  location.hash = '#/activity?tenant=research';
  route.value = parse(location.hash);
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  const rows = screen.getAllByRole('row').slice(1);
  expect(rows.length).toBeGreaterThan(0);
  expect(rows.every((row) => row.textContent?.includes('research'))).toBe(true);
});

test('changingAFilterWritesItToTheUrl', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  fireEvent.change(screen.getByLabelText('Result'), { target: { value: 'refused' } });
  expect(location.hash).toBe('#/activity?result=refused');
});

test('saysSoWhenNothingMatches', async () => {
  location.hash = '#/activity?tenant=nobody';
  route.value = parse(location.hash);
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  expect(screen.getByText('No calls match these filters.')).toBeTruthy();
});

test('showsTheFirstRunPanelWhenThereAreNoCalls', async () => {
  serve(() => answer(200, []));
  render(<Activity />);
  await tick(0);
  await tick(0);
  expect(screen.getByText('No calls yet. Send one:')).toBeTruthy();
});

test('showsTheBannerWhenTheAuditTrailIsOff', async () => {
  serve(() => answer(409, { code: 'audit_off', message: 'off' }));
  render(<Activity />);
  await tick(0);
  expect(screen.getByText(/audit trail is off in tollgate.yaml/)).toBeTruthy();
});

test('showsTheErrorWithRetryWhenTheFirstLoadFails', async () => {
  serve(() => answer(500, { code: 'boom', message: 'It broke' }));
  render(<Activity />);
  await tick(0);
  expect(screen.getByText('It broke')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('aRefusedCallOpensADrawerWithItsTraceAndALinkToItsProof', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  fireEvent.click(rowOf('01KA3X0Y5T1V8W3Z7A2B4CDEFG'));
  const drawer = screen.getByRole('dialog');
  const trace = within(drawer).getByRole('list', { name: 'Decision trace' });
  expect(within(trace).getByText('data-class')).toBeTruthy();
  expect(within(trace).getByText('refused')).toBeTruthy();
  expect(within(trace).getByText('165 µs')).toBeTruthy();
  expect(within(trace).getByText('Data class restricted may not go to region us')).toBeTruthy();
  expect(within(drawer).getByText(/Nothing was sent to the provider/)).toBeTruthy();
  expect(within(drawer).getByText('Attributed to a user')).toBeTruthy();
  const link = within(drawer).getByRole('link', { name: 'Prove this record' });
  expect(link.getAttribute('href')).toBe('#/audit/records/01KA3X0Y5T1V8W3Z7A2B4CDEFG');
});

test('anAllowedCallShowsEveryStepAndItsOutcome', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  fireEvent.click(rowOf('01KA3WXD2F5G8H1J4K7L0MNPQR'));
  const drawer = screen.getByRole('dialog');
  const trace = within(drawer).getByRole('list', { name: 'Decision trace' });
  expect(within(trace).getAllByRole('listitem')).toHaveLength(2);
  expect(within(trace).getAllByText('allowed')).toHaveLength(2);
  expect(within(drawer).getByText('upstream_error')).toBeTruthy();
  expect(within(drawer).getByText('502')).toBeTruthy();
  expect(within(drawer).getByText('$0.002')).toBeTruthy();
});

test('aMonitoredGuardShowsThatItWouldHaveRefused', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  fireEvent.click(rowOf('01KA3WYH7J4K1L8M5N2P9QRSTU'));
  const trace = within(screen.getByRole('dialog')).getByRole('list', { name: 'Decision trace' });
  expect(within(trace).getByText('would have refused')).toBeTruthy();
  expect(within(trace).getByText('monitor')).toBeTruthy();
  expect(within(trace).getByText('Tool shell is not in the allowed list')).toBeTruthy();
});

test('aCallWithoutGuardsSaysSo', async () => {
  serve(() => answer(200, activityFixture));
  render(<Activity />);
  await tick(0);
  fireEvent.click(screen.getAllByRole('row')[1] as HTMLElement);
  expect(screen.getByText('No guards ran for this call.')).toBeTruthy();
  expect(screen.getByText('Not attributed to a user')).toBeTruthy();
});

test('pollsEveryFiveSecondsAndPausesWhileADrawerIsOpen', async () => {
  serve(() => answer(200, activityBusyFixture));
  render(<Activity />);
  await tick(0);
  expect(activityCalls()).toBe(1);
  await tick(5000);
  expect(activityCalls()).toBe(2);
  fireEvent.click(rowOf('01KA3X0Y5T1V8W3Z7A2B4CDEFG'));
  await tick(20_000);
  expect(activityCalls()).toBe(2);
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  await tick(5000);
  expect(activityCalls()).toBe(3);
});

test('pollsAgainOnceThePageIsBackAtItsTop', async () => {
  serve(() => answer(200, activityBusyFixture));
  const { container } = render(<Activity />);
  await tick(0);
  const scroller = document.createElement('div');
  container.append(scroller);
  Object.defineProperty(scroller, 'scrollTop', { configurable: true, value: 120 });
  fireEvent.scroll(scroller);
  await tick(20_000);
  expect(activityCalls()).toBe(1);
  Object.defineProperty(scroller, 'scrollTop', { configurable: true, value: 0 });
  fireEvent.scroll(scroller);
  await tick(5000);
  expect(activityCalls()).toBe(2);
});
