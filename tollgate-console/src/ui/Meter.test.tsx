import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Meter } from './Meter';

afterEach(cleanup);

test('meterStatesTheValueAndTheLimitInText', () => {
  render(<Meter label="Tokens today" value={1204} limit={50000} />);
  expect(screen.getByText('Tokens today: 1,204 of 50k')).toBeTruthy();
});

test('meterBarIsHiddenAndFilledInProportion', () => {
  const { container } = render(<Meter label="Budget" value={25} limit={100} />);
  const bar = container.querySelector('[aria-hidden="true"]') as HTMLElement;
  expect((bar.firstElementChild as HTMLElement).style.width).toBe('25%');
});

test('meterBarStopsAtFullWhenOverTheLimit', () => {
  const { container } = render(<Meter label="Budget" value={150} limit={100} />);
  const bar = container.querySelector('[aria-hidden="true"]') as HTMLElement;
  expect((bar.firstElementChild as HTMLElement).style.width).toBe('100%');
});

test('meterWithNoLimitDrawsAnEmptyBar', () => {
  const { container } = render(<Meter label="Budget" value={5} limit={0} />);
  const bar = container.querySelector('[aria-hidden="true"]') as HTMLElement;
  expect((bar.firstElementChild as HTMLElement).style.width).toBe('0%');
});
