import { get } from '../api/client';
import type { ConfigView } from '../api/types';
import { useLoaded } from './Audit/useLoaded';

export function useConfig() {
  const { data, error, retry } = useLoaded(() => get<ConfigView>('/config'), 'config');
  return { config: data, error, retry };
}
