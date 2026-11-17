import type { RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';

export const focusable = 'a[href], button:not([disabled]), input, select, textarea, [tabindex="0"]';

export function useModal(panel: RefObject<HTMLElement | null>, onEscape: () => void) {
  const [opener] = useState(() => document.activeElement);
  const escaping = useRef(onEscape);
  escaping.current = onEscape;

  useEffect(() => {
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) {
        opener.focus();
      }
    };
  }, [opener]);

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        escaping.current();
        return;
      }
      if (event.key !== 'Tab' || !panel.current) {
        return;
      }
      const items = Array.from(panel.current.querySelectorAll<HTMLElement>(focusable));
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) {
        return;
      }
      const active = document.activeElement;
      if (!panel.current.contains(active)) {
        event.preventDefault();
        first.focus();
      } else if (event.shiftKey && active === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', keys);
    return () => document.removeEventListener('keydown', keys);
  }, [panel]);
}
