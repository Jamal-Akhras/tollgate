import styles from './StatTile.module.css';

const width = 100;
const height = 28;

export function sparkPoints(series: number[]): string {
  const middle = height / 2;
  if (series.length < 2) {
    return `0,${middle} ${width},${middle}`;
  }
  const min = Math.min(...series);
  const range = Math.max(...series) - min;
  return series
    .map((value, index) => {
      const x = (index / (series.length - 1)) * width;
      const y = range === 0 ? middle : height - 2 - ((value - min) / range) * (height - 4);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(' ');
}

type StatTileProps = {
  caption: string;
  value: string;
  unit?: string;
  series: number[];
  window: string;
};

export function StatTile({ caption, value, unit, series, window }: StatTileProps) {
  return (
    <div class={styles.tile}>
      <div class={styles.caption}>{caption}</div>
      <div class={styles.figure}>
        <span class={styles.value}>{value}</span>
        {unit && <span class={styles.unit}>{unit}</span>}
      </div>
      <svg class={styles.spark} viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true">
        <polyline points={sparkPoints(series)} fill="none" stroke-width="1.5" vector-effect="non-scaling-stroke" />
      </svg>
      <div class={styles.window}>{window}</div>
    </div>
  );
}
