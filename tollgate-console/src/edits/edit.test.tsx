import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { ApiError } from '../api/client';
import {
  changeFixture,
  configFixture,
  configManagedFixture,
  createdKeyFixture,
} from '../api/fixtures';
import { Toasts } from '../ui/Toast';
import { editsOn, runEdit } from './edit';

afterEach(cleanup);

function toasts() {
  render(<Toasts />);
}

test('editsAreOnOnlyWhenTheConfigIsManaged', () => {
  expect(editsOn(configManagedFixture)).toBe(true);
  expect(editsOn(configFixture)).toBe(false);
});

test('aWrittenChangeToastsTheVersionAndReloadsTheView', async () => {
  toasts();
  const onChanged = vi.fn();
  const result = await runEdit(() => Promise.resolve(changeFixture), onChanged);
  expect(result).toBe(changeFixture);
  expect(
    await screen.findByText(`Written. Config version ${changeFixture.version.slice(0, 8)}…`),
  ).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
});

test('aCreatedKeyCountsAsWritten', async () => {
  toasts();
  const result = await runEdit(() => Promise.resolve(createdKeyFixture), vi.fn());
  expect(result).toBe(createdKeyFixture);
  expect(
    await screen.findByText(`Written. Config version ${createdKeyFixture.version.slice(0, 8)}…`),
  ).toBeTruthy();
});

test('aChangeThatChangedNothingSaysSo', async () => {
  toasts();
  const onChanged = vi.fn();
  await runEdit(() => Promise.resolve({ ...changeFixture, changed: false }), onChanged);
  expect(await screen.findByText('Nothing to change')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
});

test('aMovedVersionToastsTheServersMessageAndReloadsTheView', async () => {
  toasts();
  const onChanged = vi.fn();
  const result = await runEdit(
    () => Promise.reject(new ApiError(412, 'config_changed', 'tollgate.yaml was changed by hand')),
    onChanged,
  );
  expect(result).toBeUndefined();
  expect(await screen.findByText('tollgate.yaml was changed by hand')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
});

test('editsSwitchedOffToastAndReloadTheView', async () => {
  toasts();
  const onChanged = vi.fn();
  const result = await runEdit(
    () => Promise.reject(new ApiError(409, 'edits_off', 'Edits are off')),
    onChanged,
  );
  expect(result).toBeUndefined();
  expect(await screen.findByText('Edits were switched off on the server.')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
});

test('otherFailuresAreLeftForTheDialog', async () => {
  const onChanged = vi.fn();
  const failure = new ApiError(422, 'invalid_config', 'tenant my-app has no models');
  await expect(runEdit(() => Promise.reject(failure), onChanged)).rejects.toBe(failure);
  const missing = new ApiError(428, 'precondition_required', 'If-Match is required');
  await expect(runEdit(() => Promise.reject(missing), onChanged)).rejects.toBe(missing);
  expect(onChanged).not.toHaveBeenCalled();
});
