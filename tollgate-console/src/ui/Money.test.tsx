import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Money, moneyText } from './Money';

afterEach(cleanup);

const moneyCases: [string, string, boolean | undefined, string][] = [
  ['0.00042', 'USD', undefined, '$0.00042'],
  ['12.5000', 'EUR', undefined, '€12.5000'],
  ['3.10', 'GBP', false, '£3.10'],
  ['7.25', 'CHF', undefined, '7.25 CHF'],
  ['0', 'USD', true, 'free'],
  ['0.000', 'EUR', true, 'free'],
  ['0', 'USD', false, '$0'],
  ['0.01', 'USD', true, '$0.01'],
];

test.each(moneyCases)('moneyText(%s %s free=%s)', (amount, currency, free, text) => {
  expect(moneyText({ amount, currency, free })).toBe(text);
});

const shortCases: [string, string][] = [
  ['0.581043', '$0.58'],
  ['0.010725', '$0.011'],
  ['0.01', '$0.01'],
  ['0.00042', '$0.00042'],
  ['0.5', '$0.50'],
  ['12.5000', '$12.50'],
  ['1234.5678', '$1234.57'],
  ['50', '$50'],
  ['0', '$0'],
];

test.each(shortCases)('moneyText(%s short)', (amount, text) => {
  expect(moneyText({ amount, currency: 'USD', short: true })).toBe(text);
});

test('moneyRendersItsText', () => {
  render(<Money amount="0" currency="USD" free />);
  expect(screen.getByText('free')).toBeTruthy();
});
