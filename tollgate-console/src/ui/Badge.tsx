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

const classes: Record<BadgeKind, string> = {
  enforce: styles.enforce ?? '',
  monitor: styles.monitor ?? '',
  refused: styles.refused ?? '',
  fallback: styles.fallback ?? '',
  'dry run': styles.dry ?? '',
  unsealed: styles.dry ?? '',
  sent: styles.sent ?? '',
  waiting: styles.waiting ?? '',
  failed: styles.failed ?? '',
};

export function Badge({ kind }: { kind: BadgeKind }) {
  return <span class={`${styles.badge} ${classes[kind]}`}>{kind}</span>;
}
