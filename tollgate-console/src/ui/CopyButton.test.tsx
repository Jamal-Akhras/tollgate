import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { CopyButton } from './CopyButton';

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function stubClipboard(writeText: (text: string) => Promise<void>) {
  vi.stubGlobal('navigator', { clipboard: { writeText } });
}

test('copyButtonWritesTheTextToTheClipboard', async () => {
  const writeText = vi.fn(async () => {});
  stubClipboard(writeText);
  render(<CopyButton text="abc" />);
  fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Copied' })).toBeTruthy());
  expect(writeText).toHaveBeenCalledWith('abc');
});

test('copyButtonStaysQuietWhenTheClipboardRefuses', async () => {
  const writeText = vi.fn(async () => {
    throw new Error('denied');
  });
  stubClipboard(writeText);
  render(<CopyButton text="abc" />);
  fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
  await waitFor(() => expect(writeText).toHaveBeenCalled());
  expect(screen.getByRole('button', { name: 'Copy' })).toBeTruthy();
});
