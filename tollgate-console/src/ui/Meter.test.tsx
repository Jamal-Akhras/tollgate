import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Meter } from './Meter';

afterEach(cleanup);

test('meterStatesTheValueAndTheLimitInText', () => {
  render(<Meter label="Tokens today" value={1204} limit={50000} />);
  expect(screen.getByText('Tokens today: 1,204 of 50k')).toBeTruthy();
});

test.each([
  [25, 100, '25%'],
  [150, 100, '100%'],
  [5, 0, '0%'],
])('meterBarIsHiddenAndFilled(%s of %s) to %s', (value, limit, width) => {
  const { container } = render(<Meter label="Budget" value={value} limit={limit} />);
  const bar = container.querySelector('[aria-hidden="true"]') as HTMLElement;
  expect((bar.firstElementChild as HTMLElement).style.width).toBe(width);
});
