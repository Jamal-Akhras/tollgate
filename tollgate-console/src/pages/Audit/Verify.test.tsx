import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { verificationBusyFixture, verificationFixture } from '../../api/fixtures';
import { Verify, warningsText } from './Verify';
import { answer } from '../../testing';

const send = vi.fn();

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('readsTheTrailAndSaysItIsIntact', async () => {
  send.mockImplementation(() => Promise.resolve(answer(200, verificationFixture)));
  render(<Verify />);
  fireEvent.click(screen.getByRole('button', { name: 'Verify the whole trail' }));
  await screen.findByText('Reading the trail…');
  await screen.findByText('The audit trail is intact: 2 records in 1 sealed batches');
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/audit/verification');
  expect(screen.queryByText('Reading the trail…')).toBeNull();
  expect(screen.queryByText(warningsText)).toBeNull();
});

test('listsAFailureAndAWarningWithTheirFilesAndLinesAndExplainsTheWarning', async () => {
  const result = {
    ...verificationBusyFixture,
    intact: false,
    failures: [
      {
        file: 'tollgate-audit-00000002.jsonl',
        line: 40,
        message: 'The seal of batch 3 is not signed by the key',
      },
    ],
  };
  send.mockImplementation(() => Promise.resolve(answer(200, result)));
  render(<Verify />);
  fireEvent.click(screen.getByRole('button', { name: 'Verify the whole trail' }));
  await screen.findByText('The audit trail is not intact');
  expect(screen.getByText('tollgate-audit-00000002.jsonl:40')).toBeTruthy();
  expect(screen.getByText(/The seal of batch 3 is not signed/)).toBeTruthy();
  expect(screen.getByText('tollgate-audit-00000002.jsonl:5121')).toBeTruthy();
  expect(screen.getByText(warningsText)).toBeTruthy();
});

test('saysAnIntactTrailWithWarningsIsIntactAndExplainsTheWarning', async () => {
  send.mockImplementation(() => Promise.resolve(answer(200, verificationBusyFixture)));
  render(<Verify />);
  fireEvent.click(screen.getByRole('button', { name: 'Verify the whole trail' }));
  await screen.findByText('The audit trail is intact: 18420 records in 144 sealed batches');
  expect(screen.getByText(warningsText)).toBeTruthy();
});

test('triesAgainWhenAnotherReadIsRunning', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  try {
    let calls = 0;
    send.mockImplementation(() => {
      calls += 1;
      return Promise.resolve(
        calls === 1
          ? answer(429, { code: 'audit_busy', message: 'busy' })
          : answer(200, verificationFixture),
      );
    });
    render(<Verify />);
    fireEvent.click(screen.getByRole('button', { name: 'Verify the whole trail' }));
    await screen.findByText('Reading the trail…');
    await vi.advanceTimersByTimeAsync(2000);
    await screen.findByText(/The audit trail is intact/);
    expect(calls).toBe(2);
  } finally {
    vi.useRealTimers();
  }
});

test('showsTheServersMessageWhenTheReadFails', async () => {
  send.mockImplementation(() =>
    Promise.resolve(answer(500, { code: 'boom', message: 'Could not read the trail' })),
  );
  render(<Verify />);
  fireEvent.click(screen.getByRole('button', { name: 'Verify the whole trail' }));
  await screen.findByText('Could not read the trail');
});
