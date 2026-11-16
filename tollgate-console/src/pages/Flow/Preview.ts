import { signal } from '@preact/signals';
import { useLayoutEffect } from 'preact/hooks';

export const tollgateOn = signal(true);

export function usePreview() {
  useLayoutEffect(
    () => () => {
      tollgateOn.value = true;
    },
    [],
  );
  return tollgateOn.value;
}
