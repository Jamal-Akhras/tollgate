import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { changeFixture, configManagedFixture } from '../api/fixtures';
import { Toasts } from '../ui/Toast';
import { RevokeKey } from './RevokeKey';
import { answer } from '../testing';

const send = vi.fn();

function open(id: string) {
  const keyView = configManagedFixture.keys.find((item) => item.id === id)!;
  const onChanged = vi.fn();
  const onClose = vi.fn();
  render(
    <>
      <RevokeKey
        config={configManagedFixture}
        keyView={keyView}
        onChanged={onChanged}
        onClose={onClose}
      />
      <Toasts />
    </>,
  );
  return { onChanged, onClose };
}

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('theQuestionNamesTheKeyAndTheWarningAlwaysShowsWithFocusOnCancel', () => {
  open('a81d07b4Xp2R');
  expect(screen.getByRole('dialog', { name: 'Revoke key a81d07b4Xp2R?' })).toBeTruthy();
  expect(
    screen.getByText(
      "Applications using this key get 401 as soon as this is written. Calls already running finish. It can't be undone: the key's hash is removed, and the key itself was never kept.",
    ),
  ).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.getByText('- id: "a81d07b4Xp2R"', { exact: false })).toBeTruthy();
});

test('theLastKeySentenceAppearsOnlyForATenantsLastKey', () => {
  open('3f9a1c2eK7mQ');
  expect(
    screen.getByText(
      /It is my-app's last key, so my-app can make no calls until you create another\./,
    ),
  ).toBeTruthy();
  cleanup();
  open('a81d07b4Xp2R');
  expect(screen.queryByText(/last key/)).toBeNull();
});

test('confirmSendsDeleteWithTheVersionAndCloses', async () => {
  send.mockResolvedValue(answer(200, changeFixture));
  const { onChanged, onClose } = open('a81d07b4Xp2R');
  fireEvent.click(screen.getByRole('button', { name: 'Revoke key' }));
  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  const [path, init] = send.mock.calls[0]!;
  expect(path).toBe('/admin/v1/keys/a81d07b4Xp2R');
  expect(init.method).toBe('DELETE');
  expect(init.headers['If-Match']).toBe(`"${configManagedFixture.version}"`);
  expect(onChanged).toHaveBeenCalled();
});

test('cancelWritesNothing', () => {
  const { onClose } = open('a81d07b4Xp2R');
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(send).not.toHaveBeenCalled();
});
