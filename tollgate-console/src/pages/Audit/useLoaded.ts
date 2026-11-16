import { useEffect, useState } from 'preact/hooks';
import { ApiError } from '../../api/client';

const busyRetryMillis = 2000;

type Loaded<T> = { data: T | null; error: Error | null; busy: boolean };

export function useLoaded<T>(load: () => Promise<T>, key: string | number) {
  const [state, setState] = useState<Loaded<T>>({ data: null, error: null, busy: false });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    function run() {
      load()
        .then((data) => !stopped && setState({ data, error: null, busy: false }))
        .catch((error: Error) => {
          if (stopped) {
            return;
          }
          if (error instanceof ApiError && error.status === 429) {
            setState((before) => ({ ...before, busy: true }));
            timer = setTimeout(run, busyRetryMillis);
          } else {
            setState((before) => ({ data: before.data, error, busy: false }));
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

  return { ...state, retry: () => setAttempt(attempt + 1) };
}
