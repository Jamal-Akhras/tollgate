import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { configFixture, configManagedFixture, createdKeyFixture } from '../api/fixtures';
import type { ConfigView } from '../api/types';
import { Toasts } from '../ui/Toast';
import { CreateKey } from './CreateKey';

const send = vi.fn();
const write = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function open(config: ConfigView = configManagedFixture) {
  const onChanged = vi.fn();
  const onClose = vi.fn();
  render(
    <>
      <CreateKey config={config} onChanged={onChanged} onClose={onClose} />
      <Toasts />
    </>,
  );
  return { onChanged, onClose };
}

async function create(tenant = 'my-app') {
  fireEvent.change(screen.getByLabelText('Tenant'), { target: { value: tenant } });
  fireEvent.click(screen.getByRole('button', { name: 'Create key' }));
  await screen.findByRole('dialog', { name: 'Copy the key now' });
}

function clipboard(present: boolean) {
  Object.defineProperty(navigator, 'clipboard', {
    value: present ? { writeText: write } : undefined,
    configurable: true,
  });
}

beforeEach(() => {
  send.mockReset();
  write.mockReset();
  write.mockResolvedValue(undefined);
  clipboard(true);
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('theFirstStepAsksForATenantAndSaysOnlyTheHashIsWritten', () => {
  open();
  expect(screen.getByRole('dialog', { name: 'Create a key' })).toBeTruthy();
  expect(screen.getByText('Tollgate makes the key and writes only its hash to tollgate.yaml.')).toBeTruthy();
  expect(screen.getByText("sha256: (the new key's hash)")).toBeTruthy();
  expect((screen.getByRole('button', { name: 'Create key' }) as HTMLButtonElement).disabled).toBe(true);
});

test('withOneTenantItIsAlreadyChosen', () => {
  open(configFixture);
  expect((screen.getByLabelText('Tenant') as HTMLSelectElement).value).toBe('my-app');
  expect((screen.getByRole('button', { name: 'Create key' }) as HTMLButtonElement).disabled).toBe(false);
});

test('creatingShowsTheKeyOnceAndSendsThePathAndTheVersion', async () => {
  send.mockResolvedValue(answer(200, createdKeyFixture));
  const { onClose } = open();
  await create();
  const [path, init] = send.mock.calls[0]!;
  expect(path).toBe('/admin/v1/tenants/my-app/keys');
  expect(init.method).toBe('POST');
  expect(init.body).toBeUndefined();
  expect(init.headers['If-Match']).toBe(`"${configManagedFixture.version}"`);
  const input = screen.getByLabelText('API key for my-app') as HTMLInputElement;
  expect(input.value).toBe(createdKeyFixture.key);
  expect(input.readOnly).toBe(true);
  expect(input.getAttribute('autocomplete')).toBe('off');
  expect(screen.getByText(/This is the only time the key is shown/)).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
  expect(document.body.textContent).not.toContain('Written. Config version tg_');
});

test('escapeAndTheBackdropDoNotCloseTheKeyStep', async () => {
  send.mockResolvedValue(answer(200, createdKeyFixture));
  const { onClose } = open();
  await create();
  fireEvent.keyDown(document, { key: 'Escape' });
  fireEvent.click(document.querySelector('[aria-hidden="true"][class*=backdrop]')!);
  expect(screen.getByRole('dialog', { name: 'Copy the key now' })).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
});

test('doneClosesAndTheKeyIsNowhereInTheDocument', async () => {
  send.mockResolvedValue(answer(200, createdKeyFixture));
  const { onChanged, onClose } = open();
  await create();
  fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(onChanged).toHaveBeenCalled();
  cleanup();
  expect(document.body.textContent).not.toContain(createdKeyFixture.key);
  expect(document.querySelector('input')).toBeNull();
});

test('theKeyIsInNoToastAndNoStorage', async () => {
  send.mockResolvedValue(answer(200, createdKeyFixture));
  open();
  await create();
  expect(screen.getAllByRole('status').map((item) => item.textContent).join()).not.toContain(
    createdKeyFixture.key,
  );
  expect(JSON.stringify({ ...localStorage })).not.toContain(createdKeyFixture.key);
});

test('copyWritesTheKeyAndSaysCopied', async () => {
  send.mockResolvedValue(answer(200, createdKeyFixture));
  open();
  await create();
  fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
  await screen.findByRole('button', { name: 'Copied' });
  expect(write).toHaveBeenCalledWith(createdKeyFixture.key);
});

test('withoutAClipboardThereIsNoCopyButton', async () => {
  clipboard(false);
  send.mockResolvedValue(answer(200, createdKeyFixture));
  open();
  await create();
  expect(screen.queryByRole('button', { name: 'Copy' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Done' })).toBeTruthy();
});

test('anUnknownTenantStaysInTheFirstStepWithTheMessage', async () => {
  send.mockResolvedValue(answer(404, { code: 'tenant_not_found', message: 'No tenant named my-app' }));
  const { onClose } = open();
  fireEvent.change(screen.getByLabelText('Tenant'), { target: { value: 'my-app' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create key' }));
  await waitFor(() => expect(screen.getByText('No tenant named my-app')).toBeTruthy());
  expect(screen.getByRole('dialog', { name: 'Create a key' })).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
});