import { signal, type Signal } from '@preact/signals';
import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError } from './client';

export type Poll<T> = {
  data: Signal<T | null>;
  error: Signal<Error | null>;
  updatedAt: Signal<number | null>;
  refresh: () => void;
};

export const busyRetryMillis = 2000;

export function usePoll<T>(
  load: () => Promise<T>,
  everyMillis: number,
  paused?: () => boolean,
): Poll<T> {
  const [poll] = useState(() => ({
    data: signal<T | null>(null),
    error: signal<Error | null>(null),
    updatedAt: signal<number | null>(null),
  }));
  const latest = useRef(load);
  const again = useRef<() => void>(() => undefined);
  const holding = useRef(paused);
  latest.current = load;
  holding.current = paused;

  useEffect(() => {
    let stopped = false;
    let running = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    function schedule(millis: number) {
      clearTimeout(timer);
      if (!stopped) {
        timer = setTimeout(shown, millis);
      }
    }

    async function run() {
      if (stopped || running) {
        return;
      }
      clearTimeout(timer);
      if (holding.current?.()) {
        schedule(everyMillis);
        return;
      }
      running = true;
      let next = everyMillis;
      try {
        const result = await latest.current();
        if (stopped) {
          return;
        }
        poll.data.value = result;
        poll.error.value = null;
        poll.updatedAt.value = Date.now();
      } catch (failure) {
        if (stopped) {
          return;
        }
        if (failure instanceof ApiError && failure.status === 429) {
          next = busyRetryMillis;
        } else {
          poll.error.value = failure instanceof Error ? failure : new Error(String(failure));
        }
      } finally {
        running = false;
        if (!stopped) {
          schedule(next);
        }
      }
    }

    function shown() {
      if (!document.hidden) {
        void run();
      }
    }

    again.current = () => void run();
    document.addEventListener('visibilitychange', shown);
    shown();
    return () => {
      stopped = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', shown);
      again.current = () => undefined;
    };
  }, [poll, everyMillis]);

  return { ...poll, refresh: () => again.current() };
}
