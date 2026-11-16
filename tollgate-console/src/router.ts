import { signal } from '@preact/signals';

export type RouteName =
  | 'flow'
  | 'activity'
  | 'metrics'
  | 'usage'
  | 'audit'
  | 'batch'
  | 'record'
  | 'config'
  | 'sign-in';

export type Route = {
  name: RouteName;
  path: string;
  param: string | null;
  query: Record<string, string>;
};

const simple: RouteName[] = ['flow', 'activity', 'metrics', 'usage', 'audit', 'config', 'sign-in'];

function decoded(text: string): string {
  try {
    return decodeURIComponent(text);
  } catch {
    return text;
  }
}

export function parse(hash: string): Route {
  const text = hash.replace(/^#\/?/, '');
  const [path = '', search = ''] = text.split('?', 2);
  const query = Object.fromEntries(new URLSearchParams(search));
  const parts = path.split('/').filter((part) => part !== '');
  const first = parts[0] ?? 'flow';
  const second = parts[1];
  const third = parts[2];
  const param = third ? decoded(third) : null;
  if (first === 'audit' && parts.length === 3 && param) {
    if (second === 'batches') {
      return { name: 'batch', path: parts.join('/'), param, query };
    }
    if (second === 'records') {
      return { name: 'record', path: parts.join('/'), param, query };
    }
  }
  const name = parts.length <= 1 ? simple.find((candidate) => candidate === first) : undefined;
  return { name: name ?? 'flow', path: name ?? 'flow', param: null, query: name ? query : {} };
}

export function href(path: string, query?: Record<string, string>): string {
  const text = new URLSearchParams(query).toString();
  return `#/${path}${text ? `?${text}` : ''}`;
}

export function navigate(path: string, query?: Record<string, string>) {
  location.hash = href(path, query);
}

export const route = signal<Route>(parse(location.hash));

window.addEventListener('hashchange', () => {
  route.value = parse(location.hash);
});
