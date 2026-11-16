import { beforeEach, expect, test, vi } from 'vitest';

async function load() {
  vi.resetModules();
  return import('./session');
}

beforeEach(async () => {
  const session = await load();
  session.signOut();
});

test('startsSignedOut', async () => {
  const session = await load();
  expect(session.token.value).toBeNull();
});

test('keepsTheTokenForTheTab', async () => {
  const first = await load();
  first.signIn('abc');
  expect(first.token.value).toBe('abc');
  const second = await load();
  expect(second.token.value).toBe('abc');
});

test('signingOutForgetsTheToken', async () => {
  const first = await load();
  first.signIn('abc');
  first.signOut();
  expect(first.token.value).toBeNull();
  const second = await load();
  expect(second.token.value).toBeNull();
});
