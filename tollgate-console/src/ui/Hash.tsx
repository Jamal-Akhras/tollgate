import { CopyButton } from './CopyButton';
import styles from './Hash.module.css';

export function shortHash(value: string): string {
  if (value.length <= 11) {
    return value;
  }
  return `${value.slice(0, 6)}…${value.slice(-4)}`;
}

export function Hash({ value }: { value: string }) {
  return (
    <span class={styles.hash}>
      <span class={styles.text} title={value}>
        {shortHash(value)}
      </span>
      <CopyButton text={value} />
    </span>
  );
}
