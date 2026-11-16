import { cleanup, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { auditBusyFixture, batchesBusyFixture } from '../../api/fixtures';
import { parse, route } from '../../router';
import { Audit } from './Audit';

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function serve(routes: Record<string, () => Response>) {
  send.mockImplementation((url: string) => {
    const found = Object.entries(routes).find(([path]) => url.startsWith(`/admin/v1${path}`));
    return Promise.resolve(found ? found[1]() : answer(404, { code: 'not_found', message: 'none' }));
  });
}

beforeEach(() => {
  send.mockReset();
  location.hash = '#/audit';
  route.value = parse('#/audit');
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsTheModeKeysLastBatchAndTheSmallPrint', async () => {
  serve({
    '/audit/batches': () => answer(200, batchesBusyFixture),
    '/audit': () => answer(200, auditBusyFixture),
  });
  render(<Audit />);
  await screen.findByText('async');
  expect(screen.getByText(/Checked against the public key in this server's config/)).toBeTruthy();
  expect(screen.getByTitle(auditBusyFixture.publicKey)).toBeTruthy();
  expect(screen.getByTitle('e3b0c442')).toBeTruthy();
  expect(screen.getAllByRole('link', { name: 'Batch 4' }).length).toBeGreaterThan(0);
});

test('drawsTheChainOldestFirstEachCardLinkingToItsBatch', async () => {
  serve({
    '/audit/batches': () => answer(200, batchesBusyFixture),
    '/audit': () => answer(200, auditBusyFixture),
  });
  render(<Audit />);
  const list = await screen.findByRole('list');
  const cards = within(list).getAllByRole('link');
  const numbers = batchesBusyFixture.map((view) => view.seal.batch).sort((a, b) => a - b);
  expect(cards.map((card) => card.textContent)).toEqual(numbers.map((n) => `Batch ${n}`));
  expect(cards[0]?.getAttribute('href')).toBe(`#/audit/batches/${numbers[0]}`);
  expect(within(list).getAllByText('← previous root')).toHaveLength(numbers.length - 1);
  expect(send.mock.calls.some((call) => call[0] === '/admin/v1/audit/batches?limit=50')).toBe(true);
});

test('saysSoWhenNothingIsSealedYet', async () => {
  serve({
    '/audit/batches': () => answer(200, []),
    '/audit': () => answer(200, { ...auditBusyFixture, lastSeal: null }),
  });
  render(<Audit />);
  await screen.findByText('No sealed batches yet');
  expect(screen.getByText('None yet')).toBeTruthy();
});

test('saysSoWhenTheAuditTrailIsOff', async () => {
  serve({ '/audit': () => answer(409, { code: 'audit_off', message: 'off' }) });
  render(<Audit />);
  await screen.findByText(/audit trail is off in tollgate.yaml/);
  expect(screen.queryByRole('list')).toBeNull();
});

test('showsTheServersMessageWithRetryWhenTheHeaderFails', async () => {
  serve({ '/audit': () => answer(500, { code: 'boom', message: 'The trail could not be read' }) });
  render(<Audit />);
  await screen.findByText('The trail could not be read');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});
