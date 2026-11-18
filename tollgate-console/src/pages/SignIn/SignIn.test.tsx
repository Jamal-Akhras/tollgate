import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { infoFixture } from '../../api/fixtures';
import { signedIn, signOut } from '../../api/client';
import { SignIn } from './SignIn';
import { answer } from '../../testing';

const send = vi.fn();

function setRoute(hash: string) {
  location.hash = hash;
  window.dispatchEvent(new Event('hashchange'));
}

async function submit(value: string) {
  render(<SignIn />);
  fireEvent.input(screen.getByLabelText('Admin token'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

beforeEach(() => {
  signOut();
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setRoute('');
});

test('asksForTheAdminTokenInAPasswordField', () => {
  render(<SignIn />);
  const field = screen.getByLabelText('Admin token') as HTMLInputElement;
  expect(field.type).toBe('password');
  expect(field.getAttribute('autocomplete')).toBe('off');
  expect(field.value).toBe('');
  expect(screen.getByText(/The value of TOLLGATE_ADMIN_TOKEN/)).toBeTruthy();
});

test('storesTheTokenOnlyAfterA200AndLeavesTheSignInRoute', async () => {
  setRoute('#/sign-in');
  send.mockResolvedValue(answer(200, infoFixture));
  await submit('good-token');
  await waitFor(() => expect(signedIn.value).toBe(true));
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/info');
  expect(send.mock.calls[0]?.[1].headers.Authorization).toBe('Bearer good-token');
  expect(location.hash).toBe('#/flow');
});

test('staysOnTheRouteThatWasAsked', async () => {
  setRoute('#/usage');
  send.mockResolvedValue(answer(200, infoFixture));
  await submit('good-token');
  await waitFor(() => expect(signedIn.value).toBe(true));
  expect(location.hash).toBe('#/usage');
});

test('saysWhenTheTokenIsNotValid', async () => {
  send.mockResolvedValue(answer(401, { code: 'admin_unauthorized', message: 'No' }));
  await submit('bad-token');
  expect((await screen.findByRole('alert')).textContent).toBe("That token isn't valid");
  expect(signedIn.value).toBe(false);
  expect(document.activeElement).toBe(screen.getByLabelText('Admin token'));
});

test('explainsWhenTheAdminApiIsOff', async () => {
  send.mockResolvedValue(answer(503, { code: 'admin_disabled', message: 'Off' }));
  await submit('any-token');
  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toContain('TOLLGATE_ADMIN_TOKEN');
  expect(signedIn.value).toBe(false);
});

test('saysWhenTheApiCannotBeReached', async () => {
  send.mockRejectedValue(new TypeError('Failed to fetch'));
  await submit('any-token');
  expect((await screen.findByRole('alert')).textContent).toBe(
    `Can't reach the admin API at ${location.host}`,
  );
  expect(signedIn.value).toBe(false);
});
