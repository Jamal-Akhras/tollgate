import { cleanup, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { auditBusyFixture, batchesBusyFixture } from '../../api/fixtures';
import { Audit } from './Audit';
import { answer, at } from '../../testing';

const send = vi.fn();

function serve(routes: Record<string, () => Response>) {
  send.mockImplementation((url: string) => {
    const found = Object.entries(routes).find(([path]) => url.startsWith(`/admin/v1${path}`));
    return Promise.resolve(
      found ? found[1]() : answer(404, { code: 'not_found', message: 'none' }),
    );
  });
}

function serveTrail(batches: unknown = batchesBusyFixture, audit: unknown = auditBusyFixture) {
  serve({
    '/audit/batches': () => answer(200, batches),
    '/audit': () => answer(200, audit),
  });
}

beforeEach(() => {
  send.mockReset();
  at('#/audit');
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsTheModeKeysLastBatchAndTheSmallPrint', async () => {
  serveTrail();
  render(<Audit />);
  await screen.findByText('async');
  expect(screen.getByText(/Checked against the public key in this server's config/)).toBeTruthy();
  expect(screen.getByTitle(auditBusyFixture.publicKey)).toBeTruthy();
  expect(screen.getByTitle(auditBusyFixture.keyId)).toBeTruthy();
  expect(screen.getAllByRole('link', { name: 'Batch 4' }).length).toBeGreaterThan(0);
});

test('drawsTheChainOldestFirstEachCardLinkingToItsBatch', async () => {
  serveTrail();
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
  serveTrail([], { ...auditBusyFixture, lastSeal: null });
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
