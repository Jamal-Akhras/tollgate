import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { Switch } from './Switch';

afterEach(cleanup);

test('switchIsARealButtonThatReportsItsState', () => {
  render(<Switch label="Tollgate" checked={false} onChange={() => {}} />);
  const toggle = screen.getByRole('switch', { name: 'Tollgate' });
  expect(toggle.tagName).toBe('BUTTON');
  expect(toggle.getAttribute('aria-checked')).toBe('false');
  expect(toggle.textContent).toBe('Off');
});

test('switchShowsTheOnWordWhenChecked', () => {
  render(<Switch label="Tollgate" checked onChange={() => {}} />);
  const toggle = screen.getByRole('switch');
  expect(toggle.getAttribute('aria-checked')).toBe('true');
  expect(toggle.textContent).toBe('On');
});

test('switchAsksForTheOppositeValueWhenActivated', () => {
  const onChange = vi.fn();
  render(<Switch label="Tollgate" checked={false} onChange={onChange} />);
  fireEvent.click(screen.getByRole('switch'));
  expect(onChange).toHaveBeenCalledWith(true);
});

test('switchTakesItsWordsFromTheCaller', () => {
  render(<Switch label="Guard" checked onChange={() => {}} onWord="Enforce" offWord="Monitor" />);
  expect(screen.getByRole('switch').textContent).toBe('Enforce');
});
