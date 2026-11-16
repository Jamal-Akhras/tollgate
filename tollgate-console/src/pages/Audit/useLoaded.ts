import { useEffect, useState } from 'preact/hooks';
import { ApiError } from '../../api/client';

const busyRetryMillis = 2000;

type Loaded<T> = { data: T | null; error: Error | null; busy: boolean; key: string | number };

export function useLoaded<T>(load: () => Promise<T>, key: string | number, keepWhileLoading = false) {
  const [state, setState] = useState<Loaded<T>>({ data: null, error: null, busy: false, key });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    function run() {
      load()
        .then((data) => !stopped && setState({ data, error: null, busy: false, key }))
        .catch((error: Error) => {
          if (stopped) {
            return;
          }
          if (error instanceof ApiError && error.status === 429) {
            setState((before) => ({ ...before, busy: true, key }));
            timer = setTimeout(run, busyRetryMillis);
          } else {
            setState((before) => ({ ...before, error, busy: false, key }));
          }
        });
    }

    setState((before) => ({ ...before, error: null }));
    run();
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [key, attempt]);

  const stale = state.key !== key && !keepWhileLoading;
  const shown = stale ? { data: null, error: null, busy: false } : state;
  return { data: shown.data, error: shown.error, busy: shown.busy, retry: () => setAttempt(attempt + 1) };
}
