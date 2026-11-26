import styles from './MetricTile.module.css';

const width = 100;
const height = 28;

export function sparkRuns(series: (number | null)[]): string[] {
  if (series.length < 2) {
    return [];
  }
  const most = Math.max(0, ...series.map((value) => value ?? 0));
  const runs: string[][] = [];
  let open = false;
  series.forEach((value, index) => {
    if (value === null) {
      open = false;
      return;
    }
    const x = (index / (series.length - 1)) * width;
    const y = most === 0 ? height - 2 : height - 2 - (value / most) * (height - 4);
    if (!open) {
      runs.push([]);
      open = true;
    }
    runs[runs.length - 1]?.push(`${x.toFixed(1)},${y.toFixed(1)}`);
  });
  return runs.map((points) => (points.length === 1 ? [...points, ...points] : points).join(' '));
}

type MetricTileProps = {
  caption: string;
  value: string;
  detail: string;
  change: { text: string; bad: boolean } | null;
  series: (number | null)[];
  danger?: boolean;
};

export function MetricTile({ caption, value, detail, change, series, danger }: MetricTileProps) {
  return (
    <div class={styles.tile}>
      <div class={styles.caption}>{caption}</div>
      <div class={styles.value}>{value}</div>
      <div class={styles.detail}>{detail}</div>
      <div class={change?.bad ? `${styles.change} ${styles.bad}` : styles.change}>
        {change?.text}
      </div>
      <svg
        class={danger ? `${styles.spark} ${styles.sparkDanger}` : styles.spark}
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        {sparkRuns(series).map((points) => (
          <polyline
            key={points}
            points={points}
            fill="none"
            stroke-width="1.5"
            vector-effect="non-scaling-stroke"
          />
        ))}
      </svg>
    </div>
  );
}
