import { effect, signal } from '@preact/signals';

export type Theme = 'system' | 'dark' | 'light';

const key = 'tollgate.theme';

function isTheme(value: string | null): value is Theme {
  return value === 'system' || value === 'dark' || value === 'light';
}

function stored(): Theme {
  try {
    const value = localStorage.getItem(key);
    return isTheme(value) ? value : 'system';
  } catch {
    return 'system';
  }
}

const query = window.matchMedia('(prefers-color-scheme: dark)');

export const theme = signal<Theme>(stored());
const systemDark = signal(query.matches);

query.addEventListener('change', (event) => {
  systemDark.value = event.matches;
});

export function setTheme(next: Theme) {
  theme.value = next;
  try {
    localStorage.setItem(key, next);
  } catch {
    return;
  }
}

effect(() => {
  const dark = theme.value === 'dark' || (theme.value === 'system' && systemDark.value);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
});
