import styles from './Badge.module.css';

export type BadgeKind = 'enforce' | 'monitor' | 'refused' | 'fallback' | 'dry run' | 'unsealed';

const classes: Record<BadgeKind, string> = {
  enforce: styles.enforce ?? '',
  monitor: styles.monitor ?? '',
  refused: styles.refused ?? '',
  fallback: styles.fallback ?? '',
  'dry run': styles.dry ?? '',
  unsealed: styles.dry ?? '',
};

export function Badge({ kind }: { kind: BadgeKind }) {
  return <span class={`${styles.badge} ${classes[kind]}`}>{kind}</span>;
}
