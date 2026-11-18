import { href } from '../../router';
import { Skeleton } from '../../ui/Skeleton';
import styles from './Waiting.module.css';

export function BackToAudit() {
  return (
    <a class={styles.back} href={href('audit')}>
      ← Audit
    </a>
  );
}

export function Waiting({ height, busy }: { height: string; busy: boolean }) {
  return (
    <div aria-busy="true">
      <Skeleton height={height} />
      {busy && (
        <p class={styles.source}>
          Another long read of the audit trail is running. Trying again in a moment.
        </p>
      )}
    </div>
  );
}
