import { useEffect, useRef, useState } from 'preact/hooks';
import { DataTable, type Column } from './DataTable';
import { formatClock } from './format';
import { drawPlot, type Plot, type PlotData } from './plot';
import styles from './TimeSeries.module.css';

export type SeriesLine = { name: string; token: string; values: (number | null)[] };

type TimeSeriesProps = {
  label: string;
  unit: string;
  window: string;
  x: number[];
  lines: SeriesLine[];
  format: (value: number) => string;
  stacked?: boolean;
};

type Moment = { time: number; values: (number | null)[] };

const height = 180;

function tokenValue(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function useTheme(): string {
  const root = document.documentElement;
  const [theme, setTheme] = useState(root.dataset.theme ?? '');
  useEffect(() => {
    const watch = new MutationObserver(() => setTheme(root.dataset.theme ?? ''));
    watch.observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    return () => watch.disconnect();
  }, [root]);
  return theme;
}

function plotData(x: number[], lines: SeriesLine[]): PlotData {
  return {
    x,
    lines: lines.map((line) => ({
      name: line.name,
      color: tokenValue(line.token),
      values: line.values,
    })),
  };
}

export function TimeSeries({ label, unit, window, x, lines, format, stacked }: TimeSeriesProps) {
  const box = useRef<HTMLDivElement>(null);
  const plot = useRef<Plot | null>(null);
  const latest = useRef({ x, lines });
  const formatter = useRef(format);
  const [shown, setShown] = useState(false);
  const theme = useTheme();
  const empty = x.length === 0;
  latest.current = { x, lines };
  formatter.current = format;

  useEffect(() => {
    const parent = box.current;
    if (!parent || empty) {
      return;
    }
    let stopped = false;
    const style = getComputedStyle(document.documentElement);
    const look = {
      stacked: stacked === true,
      height,
      text: tokenValue('--text-muted'),
      grid: tokenValue('--border'),
      font: `12px ${style.getPropertyValue('--font-text').trim() || 'sans-serif'}`,
      format: (value: number) => formatter.current(value),
    };
    const first = plotData(latest.current.x, latest.current.lines);
    drawPlot(parent, first, look, parent.clientWidth || 600)
      .then((drawn) => {
        if (stopped) {
          drawn.destroy();
          return;
        }
        plot.current = drawn;
        drawn.setData(plotData(latest.current.x, latest.current.lines));
      })
      .catch(() => undefined);
    const watch = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) {
        plot.current?.resize(width);
      }
    });
    watch.observe(parent);
    return () => {
      stopped = true;
      watch.disconnect();
      plot.current?.destroy();
      plot.current = null;
    };
  }, [theme, empty, stacked]);

  useEffect(() => {
    plot.current?.setData(plotData(x, lines));
  }, [x, lines]);

  const moments: Moment[] = x
    .map((time, index) => ({ time, values: lines.map((line) => line.values[index] ?? null) }))
    .reverse();
  const columns: Column<Moment>[] = [
    { key: 'time', header: 'Time (UTC)', cell: (moment) => formatClock(moment.time) },
    ...lines.map((line, index) => ({
      key: line.name,
      header: `${line.name} (${unit})`,
      align: 'right' as const,
      cell: (moment: Moment) => {
        const value = moment.values[index];
        return value === null || value === undefined ? '–' : format(value);
      },
    })),
  ];

  return (
    <section class={styles.chart} aria-label={label}>
      <header class={styles.head}>
        <h2 class={styles.title}>{label}</h2>
        <span class={styles.window}>
          {unit}, {window}
        </span>
      </header>
      {empty ? (
        <p class={styles.empty}>No calls in this window.</p>
      ) : (
        <div class={styles.plot} ref={box} aria-hidden="true" />
      )}
      <button
        type="button"
        class={styles.toggle}
        aria-expanded={shown}
        onClick={() => setShown(!shown)}
      >
        {shown ? 'Hide data' : 'Show data'}
      </button>
      {shown && (
        <div class={styles.table}>
          <DataTable
            label={`${label}, ${window}`}
            columns={columns}
            rows={moments}
            rowKey={(moment) => String(moment.time)}
            empty="No calls in this window."
          />
        </div>
      )}
    </section>
  );
}
