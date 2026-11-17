import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { StatTile, sparkPoints } from './StatTile';

afterEach(cleanup);

test('sparkPointsDrawsAFlatLineForEmptyOrFlatSeries', () => {
  expect(sparkPoints([])).toBe('0,14 100,14');
  expect(sparkPoints([5])).toBe('0,14 100,14');
  expect(sparkPoints([3, 3, 3])).toBe('0.0,14.0 50.0,14.0 100.0,14.0');
});

test('sparkPointsPutsTheHighestValueAtTheTop', () => {
  expect(sparkPoints([0, 10])).toBe('0.0,26.0 100.0,2.0');
});

test('statTileShowsCaptionValueUnitAndWindow', () => {
  render(
    <StatTile caption="Calls" value="1,204" unit="calls" series={[1, 2, 3]} window="last 60 min" />,
  );
  expect(screen.getByText('Calls')).toBeTruthy();
  expect(screen.getByText('1,204')).toBeTruthy();
  expect(screen.getByText('calls')).toBeTruthy();
  expect(screen.getByText('last 60 min')).toBeTruthy();
});

test('statTileDrawsOnePolyline', () => {
  const { container } = render(
    <StatTile caption="Calls" value="0" series={[]} window="last 60 min" />,
  );
  expect(container.querySelectorAll('polyline')).toHaveLength(1);
});
