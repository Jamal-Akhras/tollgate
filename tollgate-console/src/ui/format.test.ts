import { expect, test } from 'vitest';
import {
  formatBytes,
  formatClock,
  formatCount,
  formatDecimal,
  formatMillis,
  relativeTime,
} from './format';

const formatDecimalCases: [number, string][] = [
  [0, '0'],
  [0.004, '0'],
  [0.4, '0.4'],
  [1.236, '1.24'],
  [99.99, '99.99'],
  [894.4, '894'],
  [12345.6, '12,346'],
];

test.each(formatDecimalCases)('formatDecimal(%s)', (input, output) => {
  expect(formatDecimal(input)).toBe(output);
});

const formatCountCases: [number, string][] = [
  [0, '0'],
  [999, '999'],
  [1204, '1,204'],
  [9999, '9,999'],
  [12_400, '12.4k'],
  [12_000, '12k'],
  [999_000, '999k'],
  [3_100_000, '3.1M'],
];

test.each(formatCountCases)('formatCount(%s)', (input, output) => {
  expect(formatCount(input)).toBe(output);
});

const formatBytesCases: [number, string][] = [
  [0, '0 B'],
  [512, '512 B'],
  [1024, '1 KB'],
  [1536, '1.5 KB'],
  [5 * 1024 * 1024, '5 MB'],
  [3 * 1024 ** 3, '3 GB'],
];

test.each(formatBytesCases)('formatBytes(%s)', (input, output) => {
  expect(formatBytes(input)).toBe(output);
});

const formatMillisCases: [number, string][] = [
  [0, '0 ms'],
  [840, '840 ms'],
  [1200, '1.2 s'],
  [2000, '2 s'],
  [120_000, '2 min'],
];

test.each(formatMillisCases)('formatMillis(%s)', (input, output) => {
  expect(formatMillis(input)).toBe(output);
});

const now = Date.parse('2026-11-16T12:00:00Z');

const relativeTimeCases: [string, string][] = [
  ['2026-11-16T12:00:00Z', 'just now'],
  ['2026-11-16T12:00:30Z', 'just now'],
  ['2026-11-16T11:59:48Z', '12 s ago'],
  ['2026-11-16T11:57:00Z', '3 min ago'],
  ['2026-11-16T10:00:00Z', '2 h ago'],
  ['2026-11-14T10:00:00Z', '2026-11-14'],
  ['not a time', 'not a time'],
];

test.each(relativeTimeCases)('relativeTime(%s)', (input, output) => {
  expect(relativeTime(input, now)).toBe(output);
});

test('formatClockShowsTheUtcTimeOfDay', () => {
  expect(formatClock(Date.parse('2026-11-16T09:05:07Z') / 1000)).toBe('09:05:07');
});
