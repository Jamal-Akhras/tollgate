import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { MetricTile, sparkRuns } from './MetricTile';

afterEach(cleanup);

test('sparkRunsDrawsFromAZeroBaselineSoQuietSeriesStayFlatAtTheBottom', () => {
  expect(sparkRuns([0, 0, 0])).toEqual(['0.0,26.0 50.0,26.0 100.0,26.0']);
  expect(sparkRuns([0, 10])).toEqual(['0.0,26.0 100.0,2.0']);
  expect(sparkRuns([])).toEqual([]);
  expect(sparkRuns([4])).toEqual([]);
});

test('sparkRunsBreaksTheLineWhereTheValueIsMissing', () => {
  expect(sparkRuns([5, null, 5, 5, null, 5])).toEqual([
    '0.0,2.0 0.0,2.0',
    '40.0,2.0 60.0,2.0',
    '100.0,2.0 100.0,2.0',
  ]);
});

test('metricTileShowsCaptionValueDetailAndTheChange', () => {
  const { container } = render(
    <MetricTile
      caption="Failed"
      value="1.2%"
      detail="14 calls"
      change={{ text: '+0.4 pt vs the 15 minutes before', bad: true }}
      series={[1, 2, 3]}
      danger
    />,
  );
  expect(screen.getByText('Failed')).toBeTruthy();
  expect(screen.getByText('1.2%')).toBeTruthy();
  expect(screen.getByText('14 calls')).toBeTruthy();
  expect(screen.getByText('+0.4 pt vs the 15 minutes before')).toBeTruthy();
  expect(container.querySelectorAll('polyline')).toHaveLength(1);
});

test('metricTileLeavesTheChangeEmptyWithoutOne', () => {
  const { container } = render(
    <MetricTile caption="Calls" value="0" detail="0 per minute" change={null} series={[]} />,
  );
  expect(container.querySelectorAll('polyline')).toHaveLength(0);
  expect(screen.queryByText(/vs the/)).toBeNull();
});
