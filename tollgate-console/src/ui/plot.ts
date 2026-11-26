import type uPlot from 'uplot';
import { formatClock } from './format';

export type PlotLine = { name: string; color: string; values: (number | null)[] };

export type PlotData = { x: number[]; lines: PlotLine[] };

export type PlotLook = {
  stacked: boolean;
  height: number;
  text: string;
  grid: string;
  font: string;
  format: (value: number) => string;
  bars?: boolean;
  unit?: string;
  onCursor?: (index: number | null) => void;
};

export type Plot = {
  setData: (data: PlotData) => void;
  resize: (width: number) => void;
  destroy: () => void;
};

function stack(lines: PlotLine[]): (number | null)[][] {
  const totals: number[] = [];
  return lines.map((line) =>
    line.values.map((value, index) => {
      totals[index] = (totals[index] ?? 0) + (value ?? 0);
      return totals[index] ?? 0;
    }),
  );
}

export function layers(
  data: PlotData,
  look: Pick<PlotLook, 'stacked'>,
): { order: number[]; columns: uPlot.AlignedData } {
  const values = look.stacked ? stack(data.lines) : data.lines.map((line) => line.values);
  const order = data.lines.map((_line, index) => index);
  if (look.stacked) {
    order.reverse();
  }
  return { order, columns: [data.x, ...order.map((index) => values[index] ?? [])] };
}

export function clockLabels(ticks: number[]): string[] {
  const whole = ticks.every((tick) => tick % 60 === 0);
  return ticks.map((tick) => formatClock(tick).slice(0, whole ? 5 : 8));
}

export async function drawPlot(
  parent: HTMLElement,
  data: PlotData,
  look: PlotLook,
  width: number,
): Promise<Plot> {
  const [{ default: UPlot }] = await Promise.all([
    import('uplot'),
    import('uplot/dist/uPlot.min.css'),
  ]);
  let current = data;
  const bars = look.bars === true;
  const { order } = layers(data, look);
  const axis = {
    stroke: look.text,
    font: look.font,
    grid: { stroke: look.grid, width: 1 },
    ticks: { stroke: look.grid, width: 1 },
  };
  const options: uPlot.Options = {
    width,
    height: look.height,
    tzDate: (seconds) => UPlot.tzDate(new Date(seconds * 1000), 'UTC'),
    legend: { show: false },
    cursor: { drag: { x: false, y: false }, points: { show: !bars } },
    hooks: {
      setCursor: [(plot: uPlot) => look.onCursor?.(plot.cursor.idx ?? null)],
    },
    scales: {
      x: {
        range: (_plot, min, max) => {
          const gap = current.x.length > 1 ? (current.x[1] ?? 0) - (current.x[0] ?? 0) : 1;
          return bars ? [min - gap / 2, max + gap / 2] : [min, max];
        },
      },
      y: { range: (_plot, _min, max) => [0, max > 0 ? max * 1.1 : 1] },
    },
    axes: [
      { ...axis, values: (_plot, ticks) => clockLabels(ticks) },
      {
        ...axis,
        size: 64,
        label: look.unit,
        labelSize: 20,
        labelFont: look.font,
        values: (_plot, ticks) => ticks.map((tick) => look.format(tick)),
      },
    ],
    series: [
      {},
      ...order.map((index) => {
        const line = data.lines[index] as PlotLine;
        return {
          label: line.name,
          stroke: line.color,
          width: bars ? 0 : 2,
          fill: bars ? line.color : undefined,
          paths: bars ? UPlot.paths.bars?.({ size: [0.7, 48], gap: 1 }) : undefined,
          points: bars ? { show: false } : undefined,
          spanGaps: false,
          value: (_plot: uPlot, _value: number | null, _series: number, at: number | null) => {
            const shown = at === null ? null : current.lines[index]?.values[at];
            return shown === null || shown === undefined ? '–' : look.format(shown);
          },
        };
      }),
    ],
  };
  const chart = new UPlot(options, layers(data, look).columns, parent);
  return {
    setData: (next) => {
      current = next;
      chart.setData(layers(next, look).columns);
    },
    resize: (next) => chart.setSize({ width: next, height: look.height }),
    destroy: () => chart.destroy(),
  };
}
