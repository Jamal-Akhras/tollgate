import { DATA_CLASS, USER, headers } from './index.js';

export interface TollgateOptions {
  baseURL?: string;
  apiKey?: string;
  defaultHeaders?: Record<string, string>;
  dataClass?: string;
  user?: string;
}

export type Options<T> = Omit<T, keyof TollgateOptions> & TollgateOptions;

export function settings<T extends TollgateOptions>(options: T, path: string) {
  const { baseURL, apiKey, defaultHeaders, dataClass, user, ...rest } = options;
  const key = apiKey || process.env.TOLLGATE_API_KEY;
  if (!key) {
    throw new Error('Set TOLLGATE_API_KEY or pass an API key');
  }
  const address = baseURL || process.env.TOLLGATE_BASE_URL || 'http://localhost:8080';
  return {
    ...rest,
    baseURL: address.replace(/\/+$/, '') + path,
    apiKey: key,
    defaultHeaders: { ...headers({ dataClass, user }), ...defaultHeaders },
  };
}
