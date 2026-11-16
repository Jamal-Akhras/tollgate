import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { App } from './app';
import { infoFixture } from './api/fixtures';
import { signOut } from './state/session';

beforeEach(() => {
  signOut();
  vi.stubGlobal('fetch', () => Promise.resolve(new Response(JSON.stringify(infoFixture))));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsSignInWithoutAToken', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: 'Tollgate console' })).toBeTruthy();
  expect(screen.getByLabelText('Admin token')).toBeTruthy();
  expect(screen.queryByRole('navigation')).toBeNull();
});
