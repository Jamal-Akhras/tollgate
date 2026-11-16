import { render, screen } from '@testing-library/preact';
import { expect, test } from 'vitest';
import { App } from './app';

test('showsTheConsoleHeading', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: 'Tollgate console' })).toBeTruthy();
});
