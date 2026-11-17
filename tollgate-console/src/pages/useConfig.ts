import { useEffect, useState } from 'preact/hooks';
import { get } from '../api/client';
import type { ConfigView } from '../api/types';

export function useConfig() {
  const [config, setConfig] = useState<ConfigView | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [round, setRound] = useState(0);
  useEffect(() => {
    let stopped = false;
    get<ConfigView>('/config')
      .then((view) => {
        if (!stopped) {
          setConfig(view);
          setError(null);
        }
      })
      .catch((failure: unknown) => {
        if (!stopped) {
          setError(failure instanceof Error ? failure : new Error(String(failure)));
        }
      });
    return () => {
      stopped = true;
    };
  }, [round]);
  return { config, error, round, retry: () => setRound((value) => value + 1) };
}
