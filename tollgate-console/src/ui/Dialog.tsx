import type { ComponentChildren } from 'preact';
import { useEffect, useId, useRef, useState } from 'preact/hooks';
import styles from './Dialog.module.css';

type DialogProps = {
  title: string;
  onClose: () => void;
  locked?: boolean;
  children: ComponentChildren;
};

const focusable = 'a[href], button:not([disabled]), input, select, textarea, [tabindex="0"]';

export function Dialog({ title, onClose, locked = false, children }: DialogProps) {
  const titleId = useId();
  const [opener] = useState(() => document.activeElement);
  const panel = useRef<HTMLDivElement>(null);
  const closing = useRef(onClose);
  closing.current = onClose;
  const stuck = useRef(locked);
  stuck.current = locked;

  useEffect(() => {
    if (panel.current && !panel.current.contains(document.activeElement)) {
      panel.current.querySelector<HTMLElement>(focusable)?.focus();
    }
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) {
        opener.focus();
      }
    };
  }, [opener]);

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (!stuck.current) {
          closing.current();
        }
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
  }, []);

  return (
    <div class={styles.layer}>
      <div
        class={styles.backdrop}
        onClick={() => {
          if (!locked) {
            onClose();
          }
        }}
        aria-hidden="true"
      />
      <div
        class={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={panel}
      >
        <h2 class={styles.title} id={titleId}>
          {title}
        </h2>
        <div class={styles.body}>{children}</div>
      </div>
    </div>
  );
}
