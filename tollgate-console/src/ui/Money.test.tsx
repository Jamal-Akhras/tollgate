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

test('moneyRendersItsText', () => {
  render(<Money amount="0" currency="USD" free />);
  expect(screen.getByText('free')).toBeTruthy();
});
