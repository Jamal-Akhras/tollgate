import { useState } from 'preact/hooks';
import { navigate } from '../../router';
import { Card } from '../../ui/Card';
import styles from './FindRecord.module.css';

export const noRecordText = 'No audit record for that request';

export function FindRecord() {
  const [id, setId] = useState('');

  function find(event: Event) {
    event.preventDefault();
    const trimmed = id.trim();
    if (trimmed !== '') {
      navigate(`audit/records/${encodeURIComponent(trimmed)}`);
    }
  }

  return (
    <Card>
      <form class={styles.form} onSubmit={find}>
        <label class={styles.label} for="find-record">
          Find a record by trace ID
        </label>
        <div class={styles.row}>
          <input
            id="find-record"
            class={styles.input}
            type="text"
            value={id}
            placeholder="The X-Tollgate-Trace-Id header of a response"
            autocomplete="off"
            spellcheck={false}
            onInput={(event) => setId(event.currentTarget.value)}
          />
          <button type="submit" class={styles.button}>
            Find
          </button>
        </div>
      </form>
    </Card>
  );
}
