import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { usageBusyFixture } from '../../api/fixtures';
import type { Row, UsageReport } from '../../api/types';
import { Usage } from './Usage';
import { answer, at } from '../../testing';

const send = vi.fn();

function grouped(by: string, keys: string[]): UsageReport {
  const rows: Row[] = keys.map((key, index) => {
    const base = usageBusyFixture.rows[index % 3] as Row;
    return { ...base, key };
  });
  return { ...usageBusyFixture, by, rows };
}

async function show(hash: string, response: Response) {
  at(hash);
  send.mockImplementation(() => Promise.resolve(response));
  render(<Usage />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
}

beforeEach(() => {
  send.mockReset();
  vi.useFakeTimers();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test('showsTheTenantsWithTheirCallsRefusalsTokensAndCost', async () => {
  await show('#/usage', answer(200, usageBusyFixture));
  const table = screen.getByRole('table', { name: 'Usage' });
  const row = within(table).getByText('support-bot').closest('tr') as HTMLElement;
  expect(within(row).getByText('9,120')).toBeTruthy();
  expect(within(row).getByText('134')).toBeTruthy();
  expect(within(row).getByText('data_class: 120, tool_not_allowed: 14')).toBeTruthy();
  expect(within(row).getByText('5.1M')).toBeTruthy();
  expect(within(row).getByText('$41.18')).toBeTruthy();
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/usage?by=tenant');
  expect(screen.getByText('Summed from the signed audit trail on each request.')).toBeTruthy();
});

test('sendsTheDatesAndGroupingFromTheUrlAsTheApiTakesThem', async () => {
  await show(
    '#/usage?from=2026-10-01&to=2026-11-01&by=model',
    answer(200, grouped('model', ['gpt'])),
  );
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/usage?from=2026-10-01&to=2026-11-01&by=model');
  expect(screen.getByRole('columnheader', { name: 'model' })).toBeTruthy();
  expect((screen.getByLabelText('From (UTC)') as HTMLInputElement).value).toBe('2026-10-01');
});

test('changingTheGroupingWritesItToTheUrl', async () => {
  await show('#/usage?from=2026-10-01&to=2026-11-01', answer(200, usageBusyFixture));
  fireEvent.change(screen.getByLabelText('By'), { target: { value: 'key' } });
  expect(location.hash).toBe('#/usage?from=2026-10-01&to=2026-11-01&by=key');
});

test('showsAKeyAsCode', async () => {
  await show('#/usage?by=key', answer(200, grouped('key', ['k-1'])));
  expect(screen.getByText('k-1').tagName).toBe('CODE');
});

test('showsAUserThroughHashWithTheNoteAndKeepsUnattributedAsText', async () => {
  const hmac = 'a'.repeat(64);
  await show('#/usage?by=user', answer(200, grouped('user', [hmac, 'unattributed'])));
  expect(screen.getByTitle(hmac).textContent).toBe('aaaaaa…aaaa');
  expect(screen.getByText('unattributed')).toBeTruthy();
  const note = screen.getByText(/Each row is a hashed user ID/);
  expect(note.textContent).toContain('java -jar tollgate.jar audit user tollgate.yaml <id>');
});

test('drawsABarPerDayWithItsTitleAndShowsTheDataOnRequest', async () => {
  await show('#/usage?by=day', answer(200, grouped('day', ['2026-11-01', '2026-11-02'])));
  const chart = screen.getByRole('img', { name: 'Calls per day' });
  expect(chart.querySelectorAll('rect')).toHaveLength(2);
  expect(chart.querySelector('rect title')?.textContent).toBe('2026-11-01: 31 calls');
  expect(screen.queryByRole('table', { name: 'Calls per day' })).toBeNull();
  const toggle = screen.getByRole('button', { name: 'Show data' });
  fireEvent.click(toggle);
  expect(toggle.getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByRole('table', { name: 'Calls per day' })).toBeTruthy();
});

test('showsTheServersMessageForABadRequestUnderTheControls', async () => {
  await show(
    '#/usage?from=nope',
    answer(400, { code: 'invalid_request', message: 'from is not a date: nope' }),
  );
  expect(screen.getByRole('alert').textContent).toBe('from is not a date: nope');
  expect(screen.queryByRole('table')).toBeNull();
});

test('saysSoWhenTheAuditTrailIsOff', async () => {
  await show('#/usage', answer(409, { code: 'audit_off', message: 'off' }));
  expect(screen.getByText(/audit trail is off in tollgate.yaml/)).toBeTruthy();
});

test('copiesTheReportAsCsv', async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  vi.stubGlobal('navigator', { clipboard: { writeText } });
  await show('#/usage', answer(200, usageBusyFixture));
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Copy as CSV' }));
  });
  expect(writeText.mock.calls[0]?.[0]).toContain('support-bot,9120,134');
  expect(screen.getByRole('button', { name: 'Copied' })).toBeTruthy();
});

test('saysSoWhenThereAreNoCalls', async () => {
  await show('#/usage', answer(200, { ...usageBusyFixture, rows: [] }));
  expect(screen.getByText('No calls in this period.')).toBeTruthy();
});
