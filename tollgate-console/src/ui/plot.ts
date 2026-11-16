import type uPlot from 'uplot';

export type PlotLine = { name: string; color: string; values: (number | null)[] };

export type PlotData = { x: number[]; lines: PlotLine[] };

export type PlotLook = {
  stacked: boolean;
  height: number;
  text: string;
  grid: string;
  font: string;
  format: (value: number) => string;
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

function columns(data: PlotData, stacked: boolean): uPlot.AlignedData {
  const values = stacked ? stack(data.lines) : data.lines.map((line) => line.values);
  return [data.x, ...values];
}

function shaded(color: string): string {
  return `color-mix(in srgb, ${color} 35%, transparent)`;
}

function clock(seconds: number): string {
  return new Date(seconds * 1000).toISOString().slice(11, 19);
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
    cursor: { drag: { x: false, y: false } },
    scales: {
      y: { range: (_plot, _min, max) => [0, max > 0 ? max * 1.1 : 1] },
    },
    axes: [
      { ...axis, values: (_plot, ticks) => ticks.map(clock) },
      { ...axis, size: 64, values: (_plot, ticks) => ticks.map((tick) => look.format(tick)) },
    ],
    series: [
      {},
      ...data.lines.map((line, index) => ({
        label: line.name,
        stroke: line.color,
        width: 2,
        fill: look.stacked && index === 0 ? shaded(line.color) : undefined,
        spanGaps: false,
        value: (_plot: uPlot, _value: number | null, _series: number, at: number | null) => {
          const shown = at === null ? null : current.lines[index]?.values[at];
          return shown === null || shown === undefined ? '–' : look.format(shown);
        },
      })),
    ],
    bands: look.stacked
      ? data.lines.slice(1).map((line, index) => ({
          series: [index + 2, index + 1] as [number, number],
          fill: shaded(line.color),
        }))
      : [],
  };
  const chart = new UPlot(options, columns(data, look.stacked), parent);
  return {
    setData: (next) => {
      current = next;
      chart.setData(columns(next, look.stacked));
    },
    resize: (next) => chart.setSize({ width: next, height: look.height }),
    destroy: () => chart.destroy(),
  };
}
