import styles from './Badge.module.css';

export type BadgeKind =
  | 'enforce'
  | 'monitor'
  | 'refused'
  | 'fallback'
  | 'dry run'
  | 'unsealed'
  | 'sent'
  | 'waiting'
  | 'failed';

export function Badge({ kind }: { kind: BadgeKind }) {
  const look = kind === 'dry run' || kind === 'unsealed' ? 'dry' : kind;
  return <span class={`${styles.badge} ${styles[look]}`}>{kind}</span>;
}
