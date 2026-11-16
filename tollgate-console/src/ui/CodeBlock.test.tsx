import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { CodeBlock } from './CodeBlock';

afterEach(cleanup);

test('codeBlockShowsTheCodeAsTextWithACopyButton', () => {
  render(<CodeBlock code={'curl <b>x</b>\nsecond line'} />);
  expect(screen.getByText(/curl <b>x<\/b>/).textContent).toBe('curl <b>x</b>\nsecond line');
  expect(screen.getByRole('button', { name: 'Copy' })).toBeTruthy();
});
