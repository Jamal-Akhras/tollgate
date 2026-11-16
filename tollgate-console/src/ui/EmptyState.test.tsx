import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { EmptyState } from './EmptyState';

afterEach(cleanup);

test('emptyStateSaysWhatWouldBeHere', () => {
  render(<EmptyState title="No calls yet" text="Send one through the gateway." />);
  expect(screen.getByRole('heading', { name: 'No calls yet' })).toBeTruthy();
  expect(screen.getByText('Send one through the gateway.')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Copy' })).toBeNull();
});

test('emptyStateShowsACopyableCommand', () => {
  render(<EmptyState title="No calls yet" text="Send one." code="curl http://localhost:8080" />);
  expect(screen.getByText('curl http://localhost:8080')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Copy' })).toBeTruthy();
});
