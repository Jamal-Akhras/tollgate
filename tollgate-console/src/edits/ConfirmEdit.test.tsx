import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { ApiError } from '../api/client';
import { configManagedFixture, configRewrittenFixture } from '../api/fixtures';
import type { ConfigView } from '../api/types';
import { ConfirmEdit } from './ConfirmEdit';
import { guardModeLines } from './yaml';

afterEach(cleanup);

function open(
  config: ConfigView,
  onConfirm: () => Promise<unknown>,
  warning?: string,
  onClose = vi.fn(),
) {
  render(
    <ConfirmEdit
      title="Monitor data-class instead of enforcing it?"
      lines={guardModeLines('default', 1, 'data-class', 'monitor')}
      config={config}
      warning={warning}
      confirmLabel="Monitor"
      onConfirm={onConfirm}
      onClose={onClose}
    >
      It will still run on every call.
    </ConfirmEdit>,
  );
  return onClose;
}

test('showsTheLinesWithTheirSignsAndWords', () => {
  open(configRewrittenFixture, () => Promise.resolve());
  expect(screen.getByRole('heading', { name: 'What changes in tollgate.yaml' })).toBeTruthy();
  expect(screen.getByText('policies.default.guards[1] (data-class)')).toBeTruthy();
  const added = screen.getByText('mode: "monitor"');
  expect(added.textContent).toBe('+ added mode: "monitor"');
});

test('showsTheFirstWriteNoticeOnlyBeforeTheFirstWrite', () => {
  open(configManagedFixture, () => Promise.resolve());
  expect(screen.getByText(/This is the first change made from the console/)).toBeTruthy();
  cleanup();
  open(configRewrittenFixture, () => Promise.resolve());
  expect(screen.queryByText(/This is the first change made from the console/)).toBeNull();
});

test('startsOnTheConfirmButtonWithoutAWarning', () => {
  open(configRewrittenFixture, () => Promise.resolve());
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Monitor' }));
});

test('startsOnCancelWithAWarning', () => {
  open(configRewrittenFixture, () => Promise.resolve(), 'Requests can reach regions.');
  expect(screen.getByText('Requests can reach regions.')).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
});

test('disablesBothButtonsWhileWritingAndThenCloses', async () => {
  let finish: () => void = () => undefined;
  const onClose = open(
    configRewrittenFixture,
    () => new Promise<void>((resolve) => (finish = resolve)),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Monitor' }));
  const writing = await screen.findByRole('button', { name: 'Writing…' });
  expect((writing as HTMLButtonElement).disabled).toBe(true);
  expect((screen.getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true);
  finish();
  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
});

test('keepsAFailureMessageInTheDialog', async () => {
  const onClose = open(configRewrittenFixture, () =>
    Promise.reject(new ApiError(422, 'invalid_config', 'tenant my-app has no models')),
  );
  fireEvent.click(screen.getByRole('button', { name: 'Monitor' }));
  expect((await screen.findByRole('alert')).textContent).toBe('tenant my-app has no models');
  expect(onClose).not.toHaveBeenCalled();
  expect((screen.getByRole('button', { name: 'Monitor' }) as HTMLButtonElement).disabled).toBe(
    false,
  );
});

test('cancelClosesWithoutWriting', () => {
  const onConfirm = vi.fn();
  const onClose = open(configRewrittenFixture, onConfirm);
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(onConfirm).not.toHaveBeenCalled();
});
