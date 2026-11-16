import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError } from '../api/client';
import type { ConfigView } from '../api/types';
import { Banner } from '../ui/Banner';
import { Dialog } from '../ui/Dialog';
import styles from './ConfirmEdit.module.css';
import type { YamlLines } from './yaml';

type ConfirmEditProps = {
  title: string;
  children: ComponentChildren;
  lines: YamlLines;
  config: ConfigView;
  warning?: string;
  note?: string;
  disabled?: boolean;
  confirmLabel: string;
  onConfirm: () => Promise<unknown>;
  onClose: () => void;
};

const firstWrite =
  'This is the first change made from the console. tollgate.yaml will be rewritten in a fixed layout and its comments will be lost. The file as it is now is kept beside it as tollgate.yaml.original.';

export function ConfirmEdit({
  title,
  children,
  lines,
  config,
  warning,
  note,
  disabled = false,
  confirmLabel,
  onConfirm,
  onClose,
}: ConfirmEditProps) {
  const [writing, setWriting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    (warning ? cancel.current : confirm.current)?.focus();
  }, []);

  async function write() {
    setWriting(true);
    setProblem(null);
    try {
      await onConfirm();
      onClose();
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.message : "Couldn't write the change");
      setWriting(false);
    }
  }

  return (
    <Dialog title={title} locked={writing} onClose={onClose}>
      <div class={styles.text}>{children}</div>
      <div>
        <h3 class={styles.heading}>What changes in tollgate.yaml</h3>
        <div class={styles.lines}>
          <div class={styles.path}>{lines.path}</div>
          {lines.removed.map((line) => (
            <div key={`-${line}`} class={styles.removed}>
              <span aria-hidden="true">- </span>
              <span class={styles.word}>removed </span>
              {line}
            </div>
          ))}
          {lines.added.map((line) => (
            <div key={`+${line}`} class={styles.added}>
              <span aria-hidden="true">+ </span>
              <span class={styles.word}>added </span>
              {line}
            </div>
          ))}
        </div>
      </div>
      {!config.rewritten && <Banner kind="warn">{firstWrite}</Banner>}
      {warning && <Banner kind="warn">{warning}</Banner>}
      {!warning && note && <Banner kind="info">{note}</Banner>}
      {problem && <Banner kind="error">{problem}</Banner>}
      <div class={styles.buttons}>
        <button
          type="button"
          class={styles.cancel}
          ref={cancel}
          disabled={writing}
          onClick={onClose}
        >
          Cancel
        </button>
        <button type="button" class={styles.confirm} ref={confirm} disabled={writing || disabled} onClick={write}>
          {writing ? 'Writing…' : confirmLabel}
        </button>
      </div>
    </Dialog>
  );
}
