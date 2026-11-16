import type { ComponentChildren } from 'preact';
import { relativeTime } from './format';
import styles from './PageHeader.module.css';

type PageHeaderProps = {
  title: string;
  note?: string;
  error?: Error | null;
  updatedAt?: number | null;
  now?: number;
  children?: ComponentChildren;
};

export function PageHeader({ title, note, error, updatedAt, now, children }: PageHeaderProps) {
  const stale = error && updatedAt != null && now !== undefined;
  return (
    <header class={styles.header}>
      <div class={styles.text}>
        <h1 class={styles.title}>{title}</h1>
        {note && <p class={styles.note}>{note}</p>}
        {stale && (
          <p class={styles.stale} role="status">
            Last updated {relativeTime(new Date(updatedAt).toISOString(), now)}. Retrying.
          </p>
        )}
      </div>
      {children && <div class={styles.controls}>{children}</div>}
    </header>
  );
}
