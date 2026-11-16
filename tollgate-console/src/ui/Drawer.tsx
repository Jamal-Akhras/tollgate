import type { ComponentChildren } from 'preact';
import { useEffect, useId, useRef, useState } from 'preact/hooks';
import { Cross } from './icons';
import styles from './Drawer.module.css';

type DrawerProps = {
  title: string;
  onClose: () => void;
  children: ComponentChildren;
};

const focusable = 'a[href], button:not([disabled]), input, select, textarea, [tabindex="0"]';

export function Drawer({ title, onClose, children }: DrawerProps) {
  const titleId = useId();
  const [opener] = useState(() => document.activeElement);
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  const closing = useRef(onClose);
  closing.current = onClose;

  useEffect(() => {
    close.current?.focus();
    return () => {
      if (opener instanceof HTMLElement && opener.isConnected) {
        opener.focus();
      }
    };
  }, [opener]);

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closing.current();
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
      <div class={styles.backdrop} onClick={onClose} aria-hidden="true" />
      <div
        class={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        ref={panel}
      >
        <div class={styles.head}>
          <h2 class={styles.title} id={titleId}>
            {title}
          </h2>
          <button
            type="button"
            class={styles.close}
            aria-label="Close"
            ref={close}
            onClick={onClose}
          >
            <Cross />
          </button>
        </div>
        <div class={styles.body}>{children}</div>
      </div>
    </div>
  );
}
