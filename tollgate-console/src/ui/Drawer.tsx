import type { ComponentChildren } from 'preact';
import { useEffect, useId, useRef } from 'preact/hooks';
import { Cross } from './icons';
import styles from './Drawer.module.css';
import { useModal } from './useModal';

type DrawerProps = {
  title: string;
  onClose: () => void;
  children: ComponentChildren;
};

export function Drawer({ title, onClose, children }: DrawerProps) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const close = useRef<HTMLButtonElement>(null);
  useModal(panel, onClose);

  useEffect(() => {
    close.current?.focus();
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
