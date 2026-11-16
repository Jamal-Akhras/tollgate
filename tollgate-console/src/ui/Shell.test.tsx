import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { App } from '../app';
import { configFixture, infoFixture } from '../api/fixtures';
import { signedIn, signInWith } from '../api/client';

const send = vi.fn();
let width = 1400;

function setRoute(hash: string) {
  location.hash = hash;
  window.dispatchEvent(new Event('hashchange'));
}

beforeEach(async () => {
  width = 1400;
  localStorage.clear();
  send.mockReset();
  send.mockImplementation((path: string) =>
    Promise.resolve(
      new Response(JSON.stringify(path.endsWith('/config') ? configFixture : infoFixture)),
    ),
  );
  vi.stubGlobal('fetch', send);
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return width <= (query.includes('799') ? 799 : 1199);
    },
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }));
  await signInWith('secret-token');
  send.mockClear();
  setRoute('#/usage');
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('marksTheCurrentLink', async () => {
  setRoute('#/usage');
  render(<App />);
  expect(screen.getByRole('link', { name: 'Usage' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Flow' }).getAttribute('aria-current')).toBeNull();
  act(() => setRoute('#/audit/records/01KA'));
  expect(screen.getByRole('link', { name: 'Audit' }).getAttribute('aria-current')).toBe('page');
  await waitFor(() => expect(screen.getByText('v0.2.0')).toBeTruthy());
});

test('showsThePageNameAsTheHeading', async () => {
  setRoute('#/config');
  render(<App />);
  expect(screen.getByRole('heading', { level: 1, name: 'Config' })).toBeTruthy();
  await waitFor(() => expect(screen.getByText('v0.2.0')).toBeTruthy());
});

test('hasASkipLinkAndLandmarks', async () => {
  render(<App />);
  expect(screen.getByRole('link', { name: 'Skip to main content' })).toBeTruthy();
  expect(screen.getByRole('banner')).toBeTruthy();
  expect(screen.getByRole('navigation', { name: 'Main' })).toBeTruthy();
  fireEvent.click(screen.getByRole('link', { name: 'Skip to main content' }));
  expect(document.activeElement).toBe(screen.getByRole('main'));
  await waitFor(() => expect(screen.getByText('v0.2.0')).toBeTruthy());
});

test('collapsesAndRemembersIt', async () => {
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: 'Collapse sidebar' }));
  expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Flow' }).getAttribute('title')).toBe('Flow');
  expect(localStorage.getItem('tollgate.sidebar')).toBe('collapsed');
  cleanup();
  render(<App />);
  expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeTruthy();
  await waitFor(() => expect(screen.getByText('v0.2.0')).toBeTruthy());
});

test('startsCollapsedOnAMediumScreen', async () => {
  width = 1000;
  render(<App />);
  expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeTruthy();
  await waitFor(() => expect(screen.getByText('v0.2.0')).toBeTruthy());
});

test('opensAndClosesTheDrawerWithTheKeyboard', async () => {
  width = 600;
  render(<App />);
  expect(screen.queryByRole('navigation')).toBeNull();
  const menu = screen.getByRole('button', { name: 'Open menu' });
  fireEvent.click(menu);
  expect(screen.getByRole('navigation', { name: 'Main' })).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('link', { name: 'Flow' }));
  fireEvent.keyDown(document, { key: 'Escape' });
  await waitFor(() => expect(screen.queryByRole('navigation')).toBeNull());
  expect(document.activeElement).toBe(menu);
});

test('closesTheDrawerOnTheBackdropAndOnNavigating', async () => {
  width = 600;
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: 'Open menu' }));
  fireEvent.click(screen.getByRole('button', { name: 'Close menu' }));
  expect(screen.queryByRole('navigation')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Open menu' }));
  fireEvent.click(screen.getByRole('link', { name: 'Metrics' }));
  expect(screen.queryByRole('navigation')).toBeNull();
});

test('showsTheHostAndTheVersionInTheInstanceChip', async () => {
  render(<App />);
  expect(await screen.findByText('v0.2.0')).toBeTruthy();
  expect(screen.getByText(location.host)).toBeTruthy();
});

test('theInstanceMenuChangesTheTheme', () => {
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: new RegExp(location.host) }));
  fireEvent.click(screen.getByRole('tab', { name: 'Light' }));
  expect(document.documentElement.dataset.theme).toBe('light');
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('tab', { name: 'Light' })).toBeNull();
});

test('signingOutFromTheMenuClearsTheTokenAndShowsSignIn', () => {
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: new RegExp(location.host) }));
  fireEvent.click(screen.getAllByRole('button', { name: 'Sign out' })[0] as HTMLElement);
  expect(signedIn.value).toBe(false);
  expect(screen.getByLabelText('Admin token')).toBeTruthy();
});

test('signingOutFromTheSidebarShowsSignInAndKeepsTheRoute', () => {
  setRoute('#/usage');
  render(<App />);
  fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  expect(signedIn.value).toBe(false);
  expect(screen.getByLabelText('Admin token')).toBeTruthy();
  expect(location.hash).toBe('#/usage');
});

test('anUnauthorisedAnswerReturnsToSignIn', async () => {
  send.mockImplementation(() =>
    Promise.resolve(new Response(JSON.stringify({ code: 'admin_unauthorized', message: 'No' }), { status: 401 })),
  );
  render(<App />);
  expect(await screen.findByLabelText('Admin token')).toBeTruthy();
});
