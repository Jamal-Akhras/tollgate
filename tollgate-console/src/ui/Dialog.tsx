import type { ComponentChildren } from 'preact';
import { useEffect, useId, useRef } from 'preact/hooks';
import styles from './Dialog.module.css';
import { focusable, useModal } from './useModal';

type DialogProps = {
  title: string;
  onClose: () => void;
  locked?: boolean;
  children: ComponentChildren;
};

export function Dialog({ title, onClose, locked = false, children }: DialogProps) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const close = () => {
    if (!locked) {
      onClose();
    }
  };
  useModal(panel, close);

  useEffect(() => {
    if (panel.current && !panel.current.contains(document.activeElement)) {
      panel.current.querySelector<HTMLElement>(focusable)?.focus();
    }
  }, []);

  return (
    <div class={styles.layer}>
      <div
        class={styles.backdrop}
        onClick={close}
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
