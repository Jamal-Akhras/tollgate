import { useState } from 'preact/hooks';
import { ApiError, post } from '../api/client';
import type { ReloadResult } from '../api/types';
import { Banner } from '../ui/Banner';
import { CodeBlock } from '../ui/CodeBlock';
import { Drawer } from '../ui/Drawer';
import { showToast } from '../ui/Toast';
import styles from './HowToChange.module.css';
import type { Snippet } from './snippets';

export function useReload(onChanged: () => void) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function reload() {
    setBusy(true);
    setProblem(null);
    try {
      const result = await post<ReloadResult>('/config/reload');
      if (result.changed) {
        showToast(`Reloaded: version ${result.version.slice(0, 8)}…`, 'success');
        onChanged();
      } else {
        showToast('Nothing changed', 'success');
      }
    } catch (failure) {
      setProblem(failure instanceof ApiError ? failure.message : "Couldn't reload the config");
    } finally {
      setBusy(false);
    }
  }

  return { reload, busy, problem };
}

type HowToChangeProps = { snippet: Snippet; onClose: () => void; onChanged: () => void };

export function HowToChange({ snippet, onClose, onChanged }: HowToChangeProps) {
  const { reload, busy, problem } = useReload(onChanged);
  return (
    <Drawer title="Change this in tollgate.yaml" onClose={onClose}>
      <div class={styles.body}>
        <p>{snippet.note}</p>
        {snippet.code && <CodeBlock code={snippet.code} />}
        <p class={styles.hint}>When the file is saved, reload the config.</p>
        {problem && <Banner kind="error">{problem}</Banner>}
        <div>
          <button type="button" class={styles.button} disabled={busy} onClick={reload}>
            Reload
          </button>
        </div>
      </div>
    </Drawer>
  );
}
