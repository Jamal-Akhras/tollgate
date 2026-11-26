import { expect, test } from 'vitest';
import { before, stepFor, windowFrom } from './windows';

test('readsOnlyTheThreeWindowsAndFallsBackToFifteen', () => {
  expect(windowFrom('5')).toBe(5);
  expect(windowFrom('60')).toBe(60);
  expect(windowFrom('7')).toBe(15);
  expect(windowFrom(undefined)).toBe(15);
});

test('usesFifteenSecondBucketsForFiveMinutesAndMinutesBeyond', () => {
  expect(stepFor(5)).toBe(15);
  expect(stepFor(15)).toBe(60);
  expect(stepFor(60)).toBe(60);
});

test('namesThePeriodBeforeTheWindow', () => {
  expect(before(15)).toBe('the 15 minutes before');
});
