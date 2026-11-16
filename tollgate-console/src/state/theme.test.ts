import { afterEach, beforeEach, expect, test, vi } from 'vitest';

type Listener = (event: { matches: boolean }) => void;

let systemDark = false;
let listeners: Listener[] = [];

function load() {
  vi.resetModules();
  return import('./theme');
}

beforeEach(() => {
  systemDark = false;
  listeners = [];
  localStorage.clear();
  vi.stubGlobal('matchMedia', () => ({
    get matches() {
      return systemDark;
    },
    addEventListener: (_: string, listener: Listener) => listeners.push(listener),
  }));
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test('followsTheSystemUntilAChoiceIsMade', async () => {
  systemDark = true;
  await load();
  expect(document.documentElement.dataset.theme).toBe('dark');
  systemDark = false;
  listeners.forEach((listener) => listener({ matches: false }));
  expect(document.documentElement.dataset.theme).toBe('light');
});

test('remembersAChoice', async () => {
  const first = await load();
  first.setTheme('dark');
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(localStorage.getItem('tollgate.theme')).toBe('dark');
  const second = await load();
  expect(second.theme.value).toBe('dark');
});

test('ignoresAStoredValueItDoesNotKnow', async () => {
  localStorage.setItem('tollgate.theme', 'purple');
  const { theme } = await load();
  expect(theme.value).toBe('system');
});

test('survivesAStorageThatThrows', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new Error('blocked');
  });
  const { theme, setTheme } = await load();
  expect(theme.value).toBe('system');
  setTheme('light');
  expect(theme.value).toBe('light');
  expect(document.documentElement.dataset.theme).toBe('light');
});
