import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { bucket, snapshot, totals } from './snapshots';
import { Tiles } from './Tiles';

afterEach(cleanup);

function tile(caption: string): HTMLElement {
  return screen.getByText(caption).parentElement as HTMLElement;
}

function texts(caption: string): string[] {
  return [...tile(caption).children].map((child) => child.textContent ?? '');
}

const current = totals({
  sent: 90,
  refused: 6,
  failed: 4,
  serverErrors: 3,
  clientErrors: 5,
  cost: { USD: '0.9' },
  p50Millis: 500,
  p95Millis: 1200,
});

const earlier = totals({
  sent: 80,
  refused: 4,
  failed: 2,
  cost: { USD: '0.45' },
  p50Millis: 480,
  p95Millis: 1000,
});

test('showsFiveTilesWithTheirFiguresAndTheChangeAgainstThePreviousWindow', () => {
  render(<Tiles live={snapshot({ totals: current, previous: earlier })} minutes={15} />);
  expect(texts('Calls').slice(0, 4)).toEqual([
    'Calls',
    '100',
    '6.67 per minute',
    '+16% vs the 15 minutes before',
  ]);
  expect(texts('Failed').slice(0, 4)).toEqual([
    'Failed',
    '7%',
    '7 calls · 3 provider errors',
    '+4.7 pt vs the 15 minutes before',
  ]);
  expect(texts('Refused').slice(0, 4)).toEqual([
    'Refused',
    '6%',
    '6 calls',
    '+1.3 pt vs the 15 minutes before',
  ]);
  expect(texts('Spend').slice(0, 4)).toEqual([
    'Spend',
    '$0.90',
    '$3.60 an hour at this pace',
    '+100% vs the 15 minutes before',
  ]);
  expect(texts('Latency p95').slice(0, 4)).toEqual([
    'Latency p95',
    '1.2 s',
    'p50 500 ms · 90 calls',
    'higher vs the 15 minutes before',
  ]);
});

test('marksAFailureRateThatWentUpInTheDangerColourOnly', () => {
  render(<Tiles live={snapshot({ totals: current, previous: earlier })} minutes={15} />);
  expect(screen.getByText('+4.7 pt vs the 15 minutes before').className).toMatch(/bad/);
  expect(screen.getByText('+1.3 pt vs the 15 minutes before').className).not.toMatch(/bad/);
  cleanup();
  const better = totals({ sent: 100, failed: 1 });
  const worse = totals({ sent: 90, failed: 10 });
  render(<Tiles live={snapshot({ totals: better, previous: worse })} minutes={15} />);
  expect(screen.getByText('-9.0 pt vs the 15 minutes before').className).not.toMatch(/bad/);
});

test('showsNoChangeWhenThereIsNoPreviousWindow', () => {
  render(<Tiles live={snapshot({ totals: current, previous: null })} minutes={60} />);
  expect(screen.queryByText(/vs the/)).toBeNull();
  expect(texts('Calls')[1]).toBe('100');
});

test('namesTheWindowBeforeInTheChange', () => {
  render(<Tiles live={snapshot({ totals: current, previous: earlier })} minutes={5} />);
  expect(screen.getByText('+16% vs the 5 minutes before')).toBeTruthy();
});

test('showsADashAndTooFewCallsWhenFewerThanTwentyCallsResponded', () => {
  const few = totals({ sent: 19, p50Millis: 400, p95Millis: 900 });
  render(<Tiles live={snapshot({ totals: few, previous: earlier })} minutes={15} />);
  expect(texts('Latency p95').slice(0, 4)).toEqual(['Latency p95', '–', 'too few calls', '']);
});

test('ignoresAPreviousLatencyBuiltOnFewerThanTwentyCalls', () => {
  const few = totals({ sent: 5, p50Millis: 400, p95Millis: 900 });
  render(<Tiles live={snapshot({ totals: current, previous: few })} minutes={15} />);
  expect(texts('Latency p95')[3]).toBe('');
});

test('showsZeroAndDashesWhenThereWereNoCalls', () => {
  render(<Tiles live={snapshot({ totals: totals(), previous: totals() })} minutes={15} />);
  expect(texts('Calls').slice(0, 4)).toEqual(['Calls', '0', '0 per minute', 'no change vs the 15 minutes before']);
  expect(texts('Failed').slice(1, 3)).toEqual(['–', '0 calls']);
  expect(texts('Refused').slice(1, 3)).toEqual(['–', '0 calls']);
  expect(texts('Spend').slice(1, 3)).toEqual(['–', 'No spend in this window']);
  expect(texts('Latency p95').slice(1, 3)).toEqual(['–', 'too few calls']);
});

test('drawsTheSparklinesFromTheSettledBuckets', () => {
  const live = snapshot({
    totals: current,
    buckets: [bucket(0, { sent: 1 }), bucket(60, { sent: 3 }), bucket(120, { sent: 9 })],
  });
  const { container } = render(<Tiles live={live} minutes={15} />);
  const lines = container.querySelectorAll('polyline');
  expect(lines.length).toBeGreaterThan(0);
  expect(lines[0]?.getAttribute('points')).toBe('0.0,18.0 100.0,2.0');
});

test('comparesRatesOverTheSecondsEachWindowCoversNotRawCounts', () => {
  const partial = totals({ sent: 40, cost: { USD: '1' }, seconds: 450 });
  const whole = totals({ sent: 40, cost: { USD: '1' }, seconds: 900 });
  render(<Tiles live={snapshot({ totals: partial, previous: whole })} minutes={15} />);
  expect(texts('Calls').slice(1, 4)).toEqual([
    '40',
    '5.33 per minute',
    '+100% vs the 15 minutes before',
  ]);
  expect(texts('Spend')[2]).toBe('$8 an hour at this pace');
  expect(texts('Spend')[3]).toBe('+100% vs the 15 minutes before');
});

test('showsADashForTheRateWhenTheWindowCoversNoSeconds', () => {
  const none = totals({ sent: 3, seconds: 0 });
  render(<Tiles live={snapshot({ totals: none, previous: earlier })} minutes={15} />);
  expect(texts('Calls').slice(2, 4)).toEqual(['–', '']);
  expect(texts('Spend')[2]).toBe('–');
});

test('doesNotCountAClientClosureAsAFailure', () => {
  const closed = totals({ sent: 90, failed: 5, clientClosed: 5, serverErrors: 1 });
  render(<Tiles live={snapshot({ totals: closed })} minutes={15} />);
  expect(texts('Failed').slice(1, 3)).toEqual(['1.1%', '1 call · 1 provider error']);
});

test('saysCallsAreCountedPerAttempt', () => {
  render(<Tiles live={snapshot({ totals: current })} minutes={15} />);
  expect(screen.getByText(/counts as one failure and two calls/)).toBeTruthy();
});

test('showsDollarZeroWhenOnlyThePreviousWindowHadSpend', () => {
  const quiet = totals({ sent: 1 });
  const paid = totals({ sent: 1, cost: { USD: '2' } });
  render(<Tiles live={snapshot({ totals: quiet, previous: paid })} minutes={15} />);
  expect(texts('Spend')[1]).toBe('$0');
});
