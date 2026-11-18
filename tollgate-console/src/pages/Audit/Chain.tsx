import { useEffect, useRef } from 'preact/hooks';
import { get } from '../../api/client';
import type { BatchView } from '../../api/types';
import { href } from '../../router';
import buttons from '../../ui/button.module.css';
import { EmptyState } from '../../ui/EmptyState';
import { Hash } from '../../ui/Hash';
import { RetryBanner } from '../../ui/RetryBanner';
import { Time } from '../../ui/Time';
import { isAuditOff } from '../auditOff';
import styles from './Chain.module.css';
import { useLoaded } from './useLoaded';
import { Waiting } from './Waiting';

export function Chain({ lastBatch }: { lastBatch: number | null }) {
  const { data, error, busy, retry } = useLoaded(
    () => get<BatchView[]>('/audit/batches', { limit: 50 }),
    lastBatch ?? 0,
    true,
  );
  const list = useRef<HTMLOListElement>(null);
  const count = data?.length ?? 0;

  useEffect(() => {
    if (list.current) {
      list.current.scrollLeft = list.current.scrollWidth;
    }
  }, [count, lastBatch]);

  if (error && !isAuditOff(error)) {
    return <RetryBanner error={error} onRetry={retry} />;
  }
  if (!data) {
    return <Waiting height="110px" busy={busy} />;
  }
  if (data.length === 0) {
    return (
      <EmptyState
        title="No sealed batches yet"
        text="Tollgate seals the audit trail in batches. The first one appears after the first call."
      />
    );
  }
  const now = Date.now();
  const ordered = [...data].sort((a, b) => a.seal.batch - b.seal.batch);
  return (
    <section aria-labelledby="chain-title">
      <h2 id="chain-title" class={styles.title}>
        The chain
      </h2>
      <p class={styles.note}>The latest {ordered.length} sealed batches, newest on the right.</p>
      <ol class={styles.list} ref={list}>
        {ordered.map((view, index) => (
          <li key={view.seal.batch} class={styles.item}>
            {index > 0 && (
              <span class={styles.link} aria-hidden="true">
                ← previous root
              </span>
            )}
            <div class={styles.card}>
              <a class={styles.number} href={href(`audit/batches/${view.seal.batch}`)}>
                Batch {view.seal.batch}
              </a>
              <span class={styles.detail}>{view.seal.records} records</span>
              <Time iso={view.seal.time} now={now} />
              <Hash value={view.seal.root} />
            </div>
          </li>
        ))}
      </ol>
    </section>
  );
}
