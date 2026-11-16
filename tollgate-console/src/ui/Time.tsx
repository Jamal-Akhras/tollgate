import { relativeTime } from './format';
import styles from './Time.module.css';

type TimeProps = { iso: string; now: number };

export function Time({ iso, now }: TimeProps) {
  return (
    <time class={styles.time} dateTime={iso} title={iso}>
      {relativeTime(iso, now)}
    </time>
  );
}
