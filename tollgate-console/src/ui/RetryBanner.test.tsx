import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { RetryBanner } from './RetryBanner';

afterEach(cleanup);

test('showsTheMessageAndAsksToTryAgain', () => {
  const retry = vi.fn();
  render(<RetryBanner error={new Error('Could not read the file')} onRetry={retry} />);
  expect(screen.getByRole('alert').textContent).toContain('Could not read the file');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(retry).toHaveBeenCalledTimes(1);
});
