import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { DataTable, type Column } from './DataTable';
import { formatClock } from './format';
import { drawPlot, type Plot, type PlotData } from './plot';
import styles from './PlotChart.module.css';
import { tokenValue, useTheme } from './theme';

export type ChartLine = { name: string; token: string; values: (number | null)[] };

type PlotChartProps = {
  title: string;
  dataLabel: string;
  unit: string;
  window: string;
  x: number[];
  lines: ChartLine[];
  format: (value: number) => string;
  stacked?: boolean;
  bars?: boolean;
  empty: string;
  actions?: ComponentChildren;
};

type Moment = { time: number; values: (number | null)[] };

const height = 280;

function plotData(x: number[], lines: ChartLine[]): PlotData {
  return {
    x,
    lines: lines.map((line) => ({
      name: line.name,
      color: tokenValue(line.token),
      values: line.values,
    })),
  };
}

export function PlotChart({
  title,
  dataLabel,
  unit,
  window,
  x,
  lines,
  format,
  stacked,
  bars,
  empty,
  actions,
}: PlotChartProps) {
  const box = useRef<HTMLDivElement>(null);
  const plot = useRef<Plot | null>(null);
  const latest = useRef({ x, lines });
  const formatter = useRef(format);
  const [shown, setShown] = useState(false);
  const [hovered, setHovered] = useState<number | null>(null);
  const theme = useTheme();
  const nothing = x.length === 0;
  const names = lines.map((line) => line.name).join('|');
  latest.current = { x, lines };
  formatter.current = format;

  useEffect(() => {
    const parent = box.current;
    if (!parent || nothing) {
      return;
    }
    let stopped = false;
    const look = {
      stacked: !!stacked,
      bars: !!bars,
      unit,
      height,
      text: tokenValue('--text-muted'),
      grid: tokenValue('--border'),
      font: `12px ${tokenValue('--font-text') || 'sans-serif'}`,
      format: (value: number) => formatter.current(value),
      onCursor: setHovered,
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
  }, [theme, nothing, stacked, bars, unit, names]);

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
  const at = hovered === null ? undefined : x[hovered];

  return (
    <section class={styles.chart} aria-label={title}>
      <header class={styles.head}>
        <h2 class={styles.title}>{title}</h2>
        {actions}
      </header>
      <div class={styles.legend}>
        <ul class={styles.keys}>
          {lines.map((line) => (
            <li key={line.name} class={styles.key}>
              <span class={styles.swatch} style={{ background: `var(${line.token})` }} />
              {line.name}
            </li>
          ))}
        </ul>
        <span class={styles.readout}>
          {at === undefined
            ? `${unit}, ${window}`
            : `${formatClock(at)} UTC: ${lines
                .map((line) => {
                  const value = hovered === null ? null : line.values[hovered];
                  const text = value === null || value === undefined ? '–' : format(value);
                  return `${line.name} ${text}`;
                })
                .join(', ')}`}
        </span>
      </div>
      {nothing ? (
        <p class={styles.empty}>{empty}</p>
      ) : (
        <div
          class={styles.plot}
          ref={box}
          aria-hidden="true"
          onMouseLeave={() => setHovered(null)}
        />
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
            label={`${dataLabel}, ${window}`}
            columns={columns}
            rows={moments}
            rowKey={(moment) => String(moment.time)}
            empty={empty}
          />
        </div>
      )}
    </section>
  );
}
