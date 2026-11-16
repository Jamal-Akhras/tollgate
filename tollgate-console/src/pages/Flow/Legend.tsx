import styles from './Legend.module.css';

function Sample({ kind }: { kind: 'sent' | 'fallback' | 'refused' }) {
  return (
    <svg width="22" height="4" aria-hidden="true">
      <line class={styles[kind]} x1="0" y1="2" x2="22" y2="2" />
    </svg>
  );
}

export function Legend() {
  return (
    <ul class={styles.legend} aria-label="Legend">
      <li>
        <Sample kind="sent" />
        Checked and sent
      </li>
      <li>
        <Sample kind="fallback" />
        Fallback
      </li>
      <li>
        <Sample kind="refused" />
        Refused
      </li>
    </ul>
  );
}
