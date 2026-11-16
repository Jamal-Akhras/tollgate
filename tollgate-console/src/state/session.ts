import { signal } from '@preact/signals';

const key = 'tollgate.token';

function stored(): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

export const token = signal<string | null>(stored());

export function signIn(value: string) {
  token.value = value;
  try {
    sessionStorage.setItem(key, value);
  } catch {
    return;
  }
}

export function signOut() {
  token.value = null;
  try {
    sessionStorage.removeItem(key);
  } catch {
    return;
  }
}
