import { expect, test } from 'vitest';
import { formatBytes, formatCount, formatMillis, formatRate, relativeTime } from './format';

test('formatCountGroupsThousandsAndAbbreviatesLargeNumbers', () => {
  const table: [number, string][] = [
    [0, '0'],
    [999, '999'],
    [1204, '1,204'],
    [9999, '9,999'],
    [12_400, '12.4k'],
    [12_000, '12k'],
    [999_000, '999k'],
    [3_100_000, '3.1M'],
  ];
  for (const [input, output] of table) {
    expect(formatCount(input), String(input)).toBe(output);
  }
});

test('formatBytesPicksTheUnit', () => {
  const table: [number, string][] = [
    [0, '0 B'],
    [512, '512 B'],
    [1024, '1 KB'],
    [1536, '1.5 KB'],
    [5 * 1024 * 1024, '5 MB'],
    [3 * 1024 ** 3, '3 GB'],
  ];
  for (const [input, output] of table) {
    expect(formatBytes(input), String(input)).toBe(output);
  }
});

test('formatMillisPicksTheUnit', () => {
  const table: [number, string][] = [
    [0, '0 ms'],
    [840, '840 ms'],
    [1200, '1.2 s'],
    [2000, '2 s'],
    [120_000, '2 min'],
  ];
  for (const [input, output] of table) {
    expect(formatMillis(input), String(input)).toBe(output);
  }
});

test('formatRateSwitchesToPerMinuteBelowOnePerSecond', () => {
  const table: [number, string][] = [
    [3.25, '3.3/s'],
    [1, '1/s'],
    [0.5, '30/min'],
    [0.01, '0.6/min'],
  ];
  for (const [input, output] of table) {
    expect(formatRate(input), String(input)).toBe(output);
  }
});

test('relativeTimeSaysHowLongAgo', () => {
  const now = Date.parse('2026-11-16T12:00:00Z');
  const table: [string, string][] = [
    ['2026-11-16T12:00:00Z', 'just now'],
    ['2026-11-16T12:00:30Z', 'just now'],
    ['2026-11-16T11:59:48Z', '12 s ago'],
    ['2026-11-16T11:57:00Z', '3 min ago'],
    ['2026-11-16T10:00:00Z', '2 h ago'],
    ['2026-11-14T10:00:00Z', '2026-11-14'],
    ['not a time', 'not a time'],
  ];
  for (const [input, output] of table) {
    expect(relativeTime(input, now), input).toBe(output);
  }
});
