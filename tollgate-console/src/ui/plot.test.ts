import { expect, test } from 'vitest';
import { clockLabels, layers, type PlotData } from './plot';

const data: PlotData = {
  x: [1000, 1060],
  lines: [
    { name: 'a', color: 'red', values: [1, null] },
    { name: 'b', color: 'blue', values: [2, 3] },
  ],
};

test('stacksTheLinesCumulativelyAndDrawsTheTallestBarFirst', () => {
  const stacked = layers(data, { stacked: true });
  expect(stacked.order).toEqual([1, 0]);
  expect(stacked.columns).toEqual([
    [1000, 1060],
    [3, 3],
    [1, 0],
  ]);
});

test('keepsTheLineOrderAndTheGapsWhenNotStacked', () => {
  const plain = layers(data, { stacked: false });
  expect(plain.order).toEqual([0, 1]);
  expect(plain.columns).toEqual([
    [1000, 1060],
    [1, null],
    [2, 3],
  ]);
});

test('dropsTheSecondsFromTheAxisWhenEveryTickIsOnAWholeMinute', () => {
  expect(clockLabels([0, 60, 300])).toEqual(['00:00', '00:01', '00:05']);
  expect(clockLabels([0, 15, 60])).toEqual(['00:00:00', '00:00:15', '00:01:00']);
});
