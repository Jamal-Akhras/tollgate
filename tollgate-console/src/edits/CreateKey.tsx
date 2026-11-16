import { useRef, useState } from 'preact/hooks';
import { post, segment } from '../api/client';
import type { ConfigView, CreatedKey } from '../api/types';
import { Dialog } from '../ui/Dialog';
import { ConfirmEdit } from './ConfirmEdit';
import { runEdit } from './edit';
import styles from './Keys.module.css';
import { newKeyLines } from './yaml';

type CreateKeyProps = {
  config: ConfigView;
  onChanged: () => void;
  onClose: () => void;
};

export function CreateKey({ config, onChanged, onClose }: CreateKeyProps) {
  const only = config.tenants.length === 1 ? config.tenants[0]?.name : undefined;
  const [tenant, setTenant] = useState(only ?? '');
  const [created, setCreated] = useState<CreatedKey | null>(null);
  const [copied, setCopied] = useState(false);
  const shown = useRef(false);

  async function create() {
    const result = await runEdit(
      () => post<CreatedKey>(`/tenants/${segment(tenant)}/keys`, undefined, config.version),
      { onChanged },
    );
    if (result) {
      shown.current = true;
      setCreated(result);
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  function done() {
    setCreated(null);
    onChanged();
    onClose();
  }

  if (created) {
    return (
      <Dialog title="Copy the key now" locked onClose={done}>
        <div class={styles.field}>
          <label for="new-key" class={styles.label}>
            API key for {created.tenant}
          </label>
          <div class={styles.keyRow}>
            <input
              id="new-key"
              class={styles.key}
              type="text"
              readOnly
              autoComplete="off"
              spellcheck={false}
              value={created.key}
              onFocus={(event) => event.currentTarget.select()}
            />
            {navigator.clipboard && (
              <button type="button" class={styles.button} onClick={() => copy(created.key)}>
                {copied ? 'Copied' : 'Copy'}
              </button>
            )}
          </div>
        </div>
        <p class={styles.text}>
          This is the only time the key is shown. Tollgate keeps its hash, not the key. If you lose it,
          revoke it and create another.
        </p>
        <button type="button" class={`${styles.button} ${styles.done}`} onClick={done}>
          Done
        </button>
      </Dialog>
    );
  }

  return (
    <ConfirmEdit
      title="Create a key"
      lines={newKeyLines(tenant)}
      config={config}
      disabled={tenant === ''}
      confirmLabel="Create key"
      onConfirm={create}
      onClose={() => {
        if (!shown.current) {
          onClose();
        }
      }}
    >
      <div class={styles.field}>
        <label for="key-tenant" class={styles.label}>
          Tenant
        </label>
        <select
          id="key-tenant"
          class={styles.select}
          value={tenant}
          onChange={(event) => setTenant(event.currentTarget.value)}
        >
          {only === undefined && <option value="">Choose a tenant</option>}
          {config.tenants.map((item) => (
            <option key={item.name} value={item.name}>
              {item.name}
            </option>
          ))}
        </select>
      </div>
      <p class={styles.text}>Tollgate makes the key and writes only its hash to tollgate.yaml.</p>
    </ConfirmEdit>
  );
}