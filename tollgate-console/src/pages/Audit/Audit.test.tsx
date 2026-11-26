import { cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  auditBusyFixture,
  batchFixture,
  batchesBusyFixture,
  recordFixture,
} from '../../api/fixtures';
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

test('listsTheChainNewestFirstEachRowLinkingToItsBatchAndTheOneBeforeIt', async () => {
  serveTrail();
  render(<Audit />);
  const list = await screen.findByRole('list');
  const rows = within(list).getAllByRole('link');
  const numbers = batchesBusyFixture.map((view) => view.seal.batch).sort((a, b) => b - a);
  expect(rows.map((row) => row.textContent)).toEqual(numbers.map((n) => `Batch ${n}`));
  expect(rows[0]?.getAttribute('href')).toBe(`#/audit/batches/${numbers[0]}`);
  expect(within(list).getAllByText('Linked')).toHaveLength(numbers.length - 1);
  expect(within(list).getByText('First batch')).toBeTruthy();
  expect(send.mock.calls.some((call) => call[0] === '/admin/v1/audit/batches?limit=50')).toBe(true);
});

test('saysSoWhenABatchDoesNotNameTheRootBeforeIt', async () => {
  const newest = Math.max(...batchesBusyFixture.map((view) => view.seal.batch));
  const cut = batchesBusyFixture.map((view) =>
    view.seal.batch === newest
      ? { ...view, seal: { ...view.seal, previous: '0'.repeat(64) } }
      : view,
  );
  serveTrail(cut);
  render(<Audit />);
  const broken = await screen.findByText('Broken link');
  expect(broken.getAttribute('title')).toBe(`Does not name the root of Batch ${newest - 1}`);
});

test('saysSoWhenABatchIsMissingFromTheMiddleOfTheChain', async () => {
  const newest = Math.max(...batchesBusyFixture.map((view) => view.seal.batch));
  serveTrail(batchesBusyFixture.filter((view) => view.seal.batch !== newest - 1));
  render(<Audit />);
  const broken = await screen.findByText('Broken link');
  expect(broken.getAttribute('title')).toBe(`Batch ${newest - 1} is missing`);
});

test('aRowOpensToShowTheBatchItsTreeAndAChosenLeafsProof', async () => {
  serve({
    '/audit/batches/4': () => answer(200, batchFixture),
    '/audit/records/': () => answer(200, recordFixture),
    '/audit/batches': () => answer(200, batchesBusyFixture),
    '/audit': () => answer(200, auditBusyFixture),
  });
  render(<Audit />);
  const toggle = await screen.findByRole('button', { name: 'Details of Batch 4' });
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  fireEvent.click(toggle);
  expect(toggle.getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByText('Signed over')).toBeTruthy();
  await screen.findByText('Signature valid');
  const intents = await screen.findAllByRole('button', { name: /^intent / });
  fireEvent.click(intents.at(-1) as HTMLElement);
  const proof = await screen.findByRole('region', { name: 'Proof for leaf 5' });
  await within(proof).findByText(/Hash the record's encoding/);
  fireEvent.click(toggle);
  expect(screen.queryByText('Signed over')).toBeNull();
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
