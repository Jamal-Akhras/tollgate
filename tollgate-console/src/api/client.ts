import { computed } from '@preact/signals';
import { signIn, signOut, token } from '../state/session';
import type { AdminError, AdminInfo } from './types';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

type Params = Record<string, string | number>;

export const signedIn = computed(() => token.value !== null);

export { signOut };

type Options = {
  params?: Params;
  headers?: Record<string, string>;
  body?: unknown;
  version?: string;
  token?: string | null;
};

function query(params: Params | undefined): string {
  if (!params) {
    return '';
  }
  const text = new URLSearchParams(
    Object.entries(params).map(([name, value]) => [name, String(value)]),
  ).toString();
  return text ? `?${text}` : '';
}

async function failure(response: Response): Promise<ApiError> {
  const body = (await response.json().catch(() => null)) as Partial<AdminError> | null;
  if (body && typeof body.code === 'string' && typeof body.message === 'string') {
    return new ApiError(response.status, body.code, body.message);
  }
  return new ApiError(response.status, String(response.status), response.statusText);
}

async function request<T>(method: string, path: string, options: Options = {}): Promise<T> {
  const bearer = options.token ?? token.value;
  const headers: Record<string, string> = { ...options.headers };
  if (options.version !== undefined) {
    headers['If-Match'] = `"${options.version}"`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (bearer) {
    headers.Authorization = `Bearer ${bearer}`;
  }
  let response: Response;
  try {
    response = await fetch(`/admin/v1${path}${query(options.params)}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'unreachable', `Can't reach the admin API at ${location.host}`);
  }
  if (!response.ok) {
    if (response.status === 401 && options.token === undefined) {
      signOut();
    }
    throw await failure(response);
  }
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError(response.status, 'invalid_response', 'The admin API sent an answer that is not JSON');
  }
}

export function get<T>(path: string, params?: Params): Promise<T> {
  return request<T>('GET', path, { params });
}

export function post<T>(path: string, body?: unknown, version?: string): Promise<T> {
  return request<T>('POST', path, { body, version });
}

export function put<T>(path: string, body: unknown, version: string): Promise<T> {
  return request<T>('PUT', path, { body, version });
}

export function del<T>(path: string, version: string): Promise<T> {
  return request<T>('DELETE', path, { version });
}

export function segment(value: string | number): string {
  return encodeURIComponent(value);
}

export async function signInWith(value: string): Promise<void> {
  await request<AdminInfo>('GET', '/info', { token: value });
  signIn(value);
}
