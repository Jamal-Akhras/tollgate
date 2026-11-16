import { formatCount } from './format';
import styles from './Meter.module.css';

type MeterProps = {
  label: string;
  value: number;
  limit: number;
  format?: (n: number) => string;
};

export function Meter({ label, value, limit, format = formatCount }: MeterProps) {
  const share = limit > 0 ? Math.min(100, Math.max(0, (value / limit) * 100)) : 0;
  return (
    <div class={styles.meter}>
      <div class={styles.text}>
        {label}: {format(value)} of {format(limit)}
      </div>
      <div class={styles.bar} aria-hidden="true">
        <div class={`${styles.fill} ${value > limit ? styles.over : ''}`} style={{ width: `${share}%` }} />
      </div>
    </div>
  );
}
