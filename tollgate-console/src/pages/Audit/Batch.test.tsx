import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { batchFixture } from '../../api/fixtures';
import type { BatchDetail } from '../../api/types';
import { levels } from '../../merkle/tree';
import { parse, route } from '../../router';
import { Batch } from './Batch';

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function show(hash: string, response: Response) {
  location.hash = hash;
  route.value = parse(hash);
  send.mockImplementation(() => Promise.resolve(response));
  render(<Batch />);
}

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsTheSealAndTheServersVerdictsAndRecomputesTheRootInTheBrowser', async () => {
  show('#/audit/batches/4', answer(200, batchFixture));
  await screen.findByText('Recomputed in your browser');
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/audit/batches/4');
  expect(screen.getByRole('heading', { name: 'Batch 4' })).toBeTruthy();
  expect(screen.getByText('Signature valid')).toBeTruthy();
  expect(screen.getAllByText('Root matches')).toHaveLength(2);
  expect(screen.getAllByTitle(batchFixture.seal.root).length).toBe(2);
  expect(screen.getAllByRole('button', { name: /^(intent|outcome) / })).toHaveLength(
    batchFixture.leaves.length,
  );
});

test('saysRootDoesNotMatchWhenTheLeavesGiveAnotherRoot', async () => {
  const changed: BatchDetail = {
    ...batchFixture,
    leaves: batchFixture.leaves.map((leaf, index) =>
      index === 0 ? { ...leaf, leafHash: 'ab'.repeat(32) } : leaf,
    ),
  };
  show('#/audit/batches/4', answer(200, changed));
  await screen.findByText('Recomputed in your browser');
  expect(screen.getByText('Root does not match')).toBeTruthy();
});

test('reportsTheServersVerdictWhenItSaysTheSignatureIsNotValid', async () => {
  show(
    '#/audit/batches/4',
    answer(200, { ...batchFixture, signatureValid: false, rootMatches: false }),
  );
  await screen.findByText('Signature not valid');
  expect(screen.getAllByText('Root does not match').length).toBeGreaterThan(0);
});

test('choosingALeafGoesToItsRecord', async () => {
  show('#/audit/batches/4', answer(200, batchFixture));
  const leaf = (await screen.findAllByRole('button', { name: /^outcome / }))[0] as HTMLElement;
  fireEvent.click(leaf);
  expect(location.hash).toBe(`#/audit/records/${batchFixture.leaves[2]?.id}`);
});

test('drawsALeafOfAnUnknownTypeWithItsTypeAndHashWithoutALinkAndItsRootStillMatches', async () => {
  const leaves = [
    ...batchFixture.leaves,
    {
      index: batchFixture.leaves.length,
      type: 'config-change',
      id: 'cfg-1',
      time: '2026-11-16T09:58:00Z',
      leafHash: 'cd34'.padEnd(64, '0'),
    },
  ];
  const nodes = await levels(leaves.map((leaf) => leaf.leafHash));
  const root = nodes[nodes.length - 1]?.hash ?? '';
  show(
    '#/audit/batches/4',
    answer(200, { ...batchFixture, leaves, seal: { ...batchFixture.seal, root } }),
  );
  await screen.findByText('Recomputed in your browser');
  expect(screen.getByRole('img', { name: 'config-change cd34…' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: /config-change/ })).toBeNull();
  expect(screen.getAllByText('Root matches')).toHaveLength(2);
});

test('showsTheServersResultOnlyWhenTheBrowserCannotHash', async () => {
  vi.stubGlobal('crypto', {});
  show('#/audit/batches/4', answer(200, batchFixture));
  await screen.findByText(/Your browser can't hash here \(it needs HTTPS or localhost\)/);
  expect(screen.queryByText('Recomputed in your browser')).toBeNull();
  expect(screen.getByText('Signature valid')).toBeTruthy();
  expect(await screen.findAllByRole('button', { name: /^(intent|outcome) / })).toHaveLength(
    batchFixture.leaves.length,
  );
});

test('saysSoForABatchThatDoesNotExist', async () => {
  show('#/audit/batches/99', answer(404, { code: 'not_found', message: 'no batch 99' }));
  await screen.findByText('No such batch');
});

test('saysSoWhenTheAuditTrailIsOff', async () => {
  show('#/audit/batches/4', answer(409, { code: 'audit_off', message: 'off' }));
  await screen.findByText(/audit trail is off in tollgate.yaml/);
});

test('saysItIsBusyAndTriesAgain', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    let calls = 0;
    send.mockImplementation(() => {
      calls += 1;
      return Promise.resolve(
        calls === 1
          ? answer(429, { code: 'audit_busy', message: 'busy' })
          : answer(200, batchFixture),
      );
    });
    location.hash = '#/audit/batches/4';
    route.value = parse('#/audit/batches/4');
    render(<Batch />);
    await screen.findByText(
      'Another long read of the audit trail is running. Trying again in a moment.',
    );
    await vi.advanceTimersByTimeAsync(2000);
    await screen.findByText('Signature valid');
    expect(calls).toBe(2);
  } finally {
    vi.useRealTimers();
  }
});

test('showsTheServersMessageWithRetry', async () => {
  show('#/audit/batches/4', answer(500, { code: 'boom', message: 'Could not read the file' }));
  await screen.findByText('Could not read the file');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('showsLoadingInsteadOfThePreviousBatchWhileTheNextOneLoads', async () => {
  show('#/audit/batches/4', answer(200, batchFixture));
  await screen.findByText('Signature valid');
  let release: (response: Response) => void = () => {};
  send.mockImplementation(() => new Promise<Response>((resolve) => (release = resolve)));
  route.value = parse('#/audit/batches/5');
  await screen.findByRole('heading', { name: 'Batch 5' });
  expect(screen.queryByText('Signature valid')).toBeNull();
  await waitFor(() => expect(send.mock.calls.at(-1)?.[0]).toBe('/admin/v1/audit/batches/5'));
  release(answer(200, { ...batchFixture, seal: { ...batchFixture.seal, batch: 5 } }));
  await screen.findByText('Signature valid');
});
