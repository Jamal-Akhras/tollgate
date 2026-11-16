import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { useState } from 'preact/hooks';
import { afterEach, expect, test } from 'vitest';
import { Segmented } from './Segmented';

afterEach(cleanup);

const options = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Beta' },
  { value: 'c', label: 'Gamma' },
];

function Harness() {
  const [value, setValue] = useState('a');
  return <Segmented label="Letters" options={options} value={value} onChange={setValue} />;
}

test('segmentedIsATablistWithOneSelectedTab', () => {
  render(<Harness />);
  expect(screen.getByRole('tablist', { name: 'Letters' })).toBeTruthy();
  const tabs = screen.getAllByRole('tab');
  expect(tabs.map((tab) => tab.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false']);
  expect(tabs.map((tab) => tab.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);
});

test('rightArrowSelectsAndFocusesTheNextTab', () => {
  render(<Harness />);
  fireEvent.keyDown(screen.getByRole('tab', { name: 'Alpha' }), { key: 'ArrowRight' });
  const beta = screen.getByRole('tab', { name: 'Beta' });
  expect(beta.getAttribute('aria-selected')).toBe('true');
  expect(document.activeElement).toBe(beta);
});

test('leftArrowWrapsToTheLastTab', () => {
  render(<Harness />);
  fireEvent.keyDown(screen.getByRole('tab', { name: 'Alpha' }), { key: 'ArrowLeft' });
  expect(screen.getByRole('tab', { name: 'Gamma' }).getAttribute('aria-selected')).toBe('true');
});

test('clickingATabSelectsIt', () => {
  render(<Harness />);
  fireEvent.click(screen.getByRole('tab', { name: 'Gamma' }));
  expect(screen.getByRole('tab', { name: 'Gamma' }).getAttribute('aria-selected')).toBe('true');
});
