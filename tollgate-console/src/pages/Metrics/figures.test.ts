import { expect, test } from 'vitest';
import {
  amountText,
  changeText,
  directionText,
  failedOf,
  failedShare,
  paceText,
  percentText,
  perMinute,
  pointsText,
  refusedShare,
  share,
  spendChange,
  spendText,
  totalCalls,
  withCurrencies,
} from './figures';
import { totals } from './snapshots';

const since = 'the 15 minutes before';

test('countsEveryCallAndCountsFailuresWithoutClientClosuresPlusProviderErrors', () => {
  const sample = totals({
    sent: 90,
    refused: 6,
    failed: 4,
    clientClosed: 1,
    serverErrors: 3,
    clientErrors: 5,
  });
  expect(totalCalls(sample)).toBe(100);
  expect(failedOf(sample)).toBe(6);
  expect(failedShare(sample)).toBeCloseTo(6, 5);
  expect(refusedShare(sample)).toBeCloseTo(6, 5);
});

test('neverDividesByZeroCalls', () => {
  expect(share(0, 0)).toBeNull();
  expect(failedShare(totals())).toBeNull();
  expect(percentText(null)).toBe('–');
});

test('roundsPercentagesToOneDecimalBelowTenAndWholeAbove', () => {
  expect(percentText(0)).toBe('0%');
  expect(percentText(0.44)).toBe('0.4%');
  expect(percentText(9.96)).toBe('10%');
  expect(percentText(12.6)).toBe('13%');
});

test('turnsACountIntoARateOverTheSecondsItCoversAndNeverDividesByZero', () => {
  expect(perMinute(80, 60)).toBe(80);
  expect(perMinute(160, 120)).toBe(80);
  expect(perMinute(5, 0)).toBeNull();
});

test('writesTheChangeInPercentBetweenTwoRates', () => {
  expect(changeText(112, 100, since)).toEqual({ text: '+12% vs the 15 minutes before', up: true });
  expect(changeText(50, 100, since)).toEqual({ text: '-50% vs the 15 minutes before', up: false });
  expect(changeText(100, 100, since)?.text).toBe('no change vs the 15 minutes before');
  expect(changeText(null, 100, since)).toBeNull();
  expect(changeText(100, null, since)).toBeNull();
});

test('doesNotDivideByAPreviousRateOfZero', () => {
  expect(changeText(0, 0, since)?.text).toBe('no change vs the 15 minutes before');
  expect(changeText(4, 0, since)).toEqual({ text: 'up from none in the 15 minutes before', up: true });
});

test('writesRateChangesInPercentagePoints', () => {
  expect(pointsText(2.4, 2, since)).toEqual({ text: '+0.4 pt vs the 15 minutes before', up: true });
  expect(pointsText(1, 3.5, since)).toEqual({ text: '-2.5 pt vs the 15 minutes before', up: false });
  expect(pointsText(2.01, 2, since)?.text).toBe('no change vs the 15 minutes before');
  expect(pointsText(null, 2, since)).toBeNull();
  expect(pointsText(2, null, since)).toBeNull();
});

test('onlySaysHigherLowerOrNoChangeForLatency', () => {
  expect(directionText(1448, 1024, since)).toEqual({ text: 'higher vs the 15 minutes before', up: true });
  expect(directionText(512, 1024, since).text).toBe('lower vs the 15 minutes before');
  expect(directionText(512, 512, since).text).toBe('no change vs the 15 minutes before');
});

test('joinsOneAmountPerCurrencyWithAPlusAndDashesWhenThereIsNoCurrency', () => {
  expect(spendText({ USD: '0.231547', EUR: '2' })).toBe('€2 + $0.23');
  expect(spendText({})).toBe('–');
  expect(spendText(withCurrencies({}, ['USD']))).toBe('$0');
  expect(withCurrencies({ USD: '2' }, ['USD', 'EUR'])).toEqual({ USD: '2', EUR: '0' });
});

test('scalesTheSpendToAnHourOverTheSecondsItCovers', () => {
  expect(paceText({ USD: '0.25' }, 900)).toBe('$1 an hour at this pace');
  expect(paceText({ USD: '0.25' }, 450)).toBe('$2 an hour at this pace');
  expect(paceText({ USD: '0.0231547' }, 3600)).toBe('$0.023 an hour at this pace');
  expect(paceText({ USD: '0' }, 900)).toBe('No spend in this window');
  expect(paceText({}, 900)).toBe('No spend in this window');
  expect(paceText({ USD: '1' }, 0)).toBe('–');
  expect(amountText(0.42, 'USD')).toBe('$0.42');
});

test('comparesSpendRatesNotSumsAndOnlyWithOneCurrency', () => {
  const now = totals({ cost: { USD: '3' }, seconds: 450 });
  const before = totals({ cost: { USD: '3' }, seconds: 900 });
  expect(spendChange(now, before, since)?.text).toBe('+100% vs the 15 minutes before');
  const more = totals({ cost: { USD: '3' }, seconds: 900 });
  const less = totals({ cost: { USD: '2' }, seconds: 900 });
  expect(spendChange(more, less, since)?.text).toBe('+50% vs the 15 minutes before');
  expect(spendChange(more, totals({ seconds: 900 }), since)?.up).toBe(true);
  const both = totals({ cost: { USD: '3', EUR: '1' } });
  expect(spendChange(both, less, since)).toBeNull();
  expect(spendChange(totals(), totals(), since)).toBeNull();
  expect(spendChange(now, totals({ cost: { USD: '3' }, seconds: 0 }), since)).toBeNull();
});
