import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { Pill } from './Pill';

afterEach(cleanup);

test('pillShowsLabelAndDetailInsideAButton', () => {
  render(<Pill label="Budget" detail="$4 of $10" state="ok" />);
  const button = screen.getByRole('button', { name: /Budget/ });
  expect(button.textContent).toContain('$4 of $10');
});

test('pillReportsWhetherItIsSelected', () => {
  const { rerender } = render(<Pill label="Guard" detail="enforce" state="ok" />);
  expect(screen.getByRole('button').getAttribute('aria-pressed')).toBe('false');
  rerender(<Pill label="Guard" detail="enforce" state="ok" selected />);
  expect(screen.getByRole('button').getAttribute('aria-pressed')).toBe('true');
});

test('pillMarksEachStateWithMoreThanColour', () => {
  const states = ['ok', 'monitor', 'off'] as const;
  for (const state of states) {
    const { container } = render(<Pill label="x" detail="y" state={state} />);
    expect(container.querySelector(`[data-state="${state}"]`), state).toBeTruthy();
    cleanup();
  }
  const { container } = render(<Pill label="x" detail="y" state="refused" />);
  expect(container.querySelector('svg')).toBeTruthy();
});

test('pillCallsOnClick', () => {
  const onClick = vi.fn();
  render(<Pill label="Guard" detail="enforce" state="ok" onClick={onClick} />);
  fireEvent.click(screen.getByRole('button'));
  expect(onClick).toHaveBeenCalledTimes(1);
});
