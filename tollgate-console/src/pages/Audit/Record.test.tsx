import { cleanup, configure, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { recordFixture } from '../../api/fixtures';
import type { ProvedRecord, RecordProofs } from '../../api/types';
import { parse, route } from '../../router';
import { cannotHashText } from './ProofSteps';
import { AuditRecord } from './Record';

configure({ asyncUtilTimeout: 20000 });

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function show(hash: string, response: Response) {
  location.hash = hash;
  route.value = parse(hash);
  send.mockImplementation(() => Promise.resolve(response));
  render(<AuditRecord />);
}

const unsealed: ProvedRecord = { record: { type: 'intent', id: 'request-5' }, sealed: false, proof: null, seal: null, signatureValid: null };

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('checksTheIntentsProofInTheBrowserAndEndsInEqual', async () => {
  show('#/audit/records/request-5', answer(200, recordFixture));
  await screen.findByText('Checked in your browser');
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/audit/records/request-5');
  expect(screen.getByText('Hash the record\'s encoding')).toBeTruthy();
  expect(screen.getAllByText(/^Combine with/)).toHaveLength(3);
  expect(screen.getAllByText('equal')).toHaveLength(3);
  expect(screen.queryByText('not equal')).toBeNull();
  expect(screen.getByText('valid')).toBeTruthy();
  expect(screen.getByText(/"tenant": "support-bot"/)).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Leaf 5 of 7 in batch 4' }).getAttribute('href')).toBe('#/audit/batches/4?leaf=5');
});

test('showsTheOutcomeUnderItsOwnTab', async () => {
  show('#/audit/records/request-5', answer(200, recordFixture));
  await screen.findByText('Checked in your browser');
  fireEvent.click(screen.getByRole('tab', { name: 'Outcome' }));
  await screen.findByText(/"result": "upstream_error"/);
  await screen.findByText('Checked in your browser');
  expect(screen.getAllByText('equal')).toHaveLength(3);
});

test('showsNoTabsWhenThereIsOnlyAnIntent', async () => {
  show('#/audit/records/request-5', answer(200, { ...recordFixture, outcome: null }));
  await screen.findByText('Checked in your browser');
  expect(screen.queryByRole('tab')).toBeNull();
});

test('saysNotEqualAndWhatRootItReachedWhenASiblingIsChanged', async () => {
  const intent = recordFixture.intent as ProvedRecord;
  const proof = intent.proof as NonNullable<ProvedRecord['proof']>;
  const changed: RecordProofs = {
    intent: { ...intent, proof: { ...proof, path: [proof.path[0] as string, 'ff'.repeat(32), proof.path[2] as string] } },
    outcome: null,
  };
  show('#/audit/records/request-5', answer(200, changed));
  await screen.findByText(/The proof does not hold/);
  expect(screen.getAllByText('not equal').length).toBeGreaterThan(0);
  expect(screen.queryByText('Checked in your browser')).toBeNull();
  expect(screen.getAllByTitle(proof.root).length).toBeGreaterThan(0);
});

test('saysNotEqualWhenTheEncodingDoesNotHashToTheLeafHash', async () => {
  const intent = recordFixture.intent as ProvedRecord;
  const proof = intent.proof as NonNullable<ProvedRecord['proof']>;
  show('#/audit/records/request-5', answer(200, { intent: { ...intent, proof: { ...proof, leafHash: 'aa'.repeat(32) } }, outcome: null }));
  await screen.findByText(/The proof does not hold/);
});

test('saysNotEqualWhenTheProofRootIsNotTheSealsRoot', async () => {
  const intent = recordFixture.intent as ProvedRecord;
  const seal = intent.seal as NonNullable<ProvedRecord['seal']>;
  show('#/audit/records/request-5', answer(200, { intent: { ...intent, seal: { ...seal, root: 'bb'.repeat(32) } }, outcome: null }));
  await screen.findByText(/The proof does not hold/);
  expect(screen.getAllByText('not equal')).toHaveLength(1);
});

test('showsTheServersResultAndTheExplanationWhenTheBrowserCannotHash', async () => {
  vi.stubGlobal('crypto', {});
  show('#/audit/records/request-5', answer(200, recordFixture));
  await screen.findByText(cannotHashText);
  expect(screen.queryByText('Checked in your browser')).toBeNull();
  expect(screen.getByText('valid')).toBeTruthy();
  expect(screen.getByText(/Combined with 3 siblings/)).toBeTruthy();
});

test('asksForARecordWhoseIdHasADotWithItEncoded', async () => {
  show('#/audit/records/abc.2', answer(200, { ...recordFixture, outcome: null }));
  await screen.findByText('Checked in your browser');
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/audit/records/abc.2');
});

test('asksAgainEveryTwoSecondsForAnUnsealedRecordUpToFiveTimesThenOffersToCheckAgain', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    send.mockImplementation(() => Promise.resolve(answer(200, { intent: unsealed, outcome: null })));
    location.hash = '#/audit/records/request-5';
    route.value = parse('#/audit/records/request-5');
    render(<AuditRecord />);
    await screen.findByText('Not sealed yet. Batches close within a second');
    for (let i = 0; i < 5; i += 1) {
      await vi.advanceTimersByTimeAsync(2000);
    }
    await vi.advanceTimersByTimeAsync(10000);
    expect(send).toHaveBeenCalledTimes(6);
    await screen.findByRole('button', { name: 'Check again' });
  } finally {
    vi.useRealTimers();
  }
});

test('showsTheProofOnceTheRecordIsSealed', async () => {
  let calls = 0;
  send.mockImplementation(() => {
    calls += 1;
    return Promise.resolve(
      answer(200, calls === 1 ? { intent: unsealed, outcome: null } : { ...recordFixture, outcome: null }),
    );
  });
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    location.hash = '#/audit/records/request-5';
    route.value = parse('#/audit/records/request-5');
    render(<AuditRecord />);
    await screen.findByText('Not sealed yet. Batches close within a second');
    await vi.advanceTimersByTimeAsync(2000);
  } finally {
    vi.useRealTimers();
  }
  await screen.findByText('Checked in your browser');
  expect(screen.queryByText('Not sealed yet. Batches close within a second')).toBeNull();
  expect(calls).toBe(2);
});

test('saysSoForARequestWithNoRecord', async () => {
  show('#/audit/records/nope', answer(404, { code: 'not_found', message: 'none' }));
  await screen.findByText('No audit record for that request');
});

test('saysSoWhenTheAuditTrailIsOff', async () => {
  show('#/audit/records/request-5', answer(409, { code: 'audit_off', message: 'off' }));
  await screen.findByText(/audit trail is off in tollgate.yaml/);
});

test('showsTheServersMessageWithRetry', async () => {
  show('#/audit/records/request-5', answer(500, { code: 'boom', message: 'Could not read the file' }));
  await screen.findByText('Could not read the file');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});