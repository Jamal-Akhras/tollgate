import { act, cleanup, render } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { PlotChart, type ChartLine } from './PlotChart';

const draw = vi.hoisted(() => vi.fn());

vi.mock('./plot', () => ({ drawPlot: draw }));

class Observer {
  observe() {}
  disconnect() {}
}

beforeEach(() => {
  draw.mockReset();
  draw.mockResolvedValue({ setData: vi.fn(), resize: vi.fn(), destroy: vi.fn() });
  vi.stubGlobal('ResizeObserver', Observer);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function chart(lines: ChartLine[]) {
  return (
    <PlotChart
      title="Over time"
      dataLabel="Spend"
      unit="per minute"
      window="last 15 minutes"
      x={[1, 2]}
      lines={lines}
      format={String}
      bars
      empty="Nothing"
    />
  );
}

test('rebuildsThePlotWhenTheSetOfSeriesChangesButNotWhenOnlyValuesDo', async () => {
  const usd = { name: 'USD', token: '--accent', values: [1, 2] };
  const view = render(chart([usd]));
  await act(async () => undefined);
  expect(draw).toHaveBeenCalledTimes(1);
  view.rerender(chart([{ ...usd, values: [3, 4] }]));
  await act(async () => undefined);
  expect(draw).toHaveBeenCalledTimes(1);
  view.rerender(chart([usd, { name: 'EUR', token: '--info', values: [1, 1] }]));
  await act(async () => undefined);
  expect(draw).toHaveBeenCalledTimes(2);
});
