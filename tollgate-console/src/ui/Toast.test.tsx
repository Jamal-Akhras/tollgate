import { act, cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { showToast, Toasts } from './Toast';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

test('showsAToastInAStatusRegionAndRemovesItAfterFiveSeconds', () => {
  render(<Toasts />);
  act(() => showToast('Budget saved', 'success'));
  expect(screen.getByRole('status').textContent).toContain('Budget saved');
  act(() => {
    vi.advanceTimersByTime(4999);
  });
  expect(screen.queryByText('Budget saved')).toBeTruthy();
  act(() => {
    vi.advanceTimersByTime(1);
  });
  expect(screen.queryByText('Budget saved')).toBeNull();
});

test('theCloseButtonRemovesOnlyItsToast', () => {
  render(<Toasts />);
  act(() => {
    showToast('First', 'success');
    showToast('Second', 'error');
  });
  fireEvent.click(screen.getAllByRole('button', { name: 'Dismiss' })[0] as HTMLElement);
  expect(screen.queryByText('First')).toBeNull();
  expect(screen.getByText('Second')).toBeTruthy();
  act(() => {
    vi.advanceTimersByTime(5000);
  });
});
