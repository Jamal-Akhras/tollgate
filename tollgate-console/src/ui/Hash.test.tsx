import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Hash, shortHash } from './Hash';

afterEach(cleanup);

const full = '7c0e4b1d9f2a8c3e5b6d7a0f1e2c3d4b5a69ab2';

const shortHashCases: [string, string][] = [
  [full, '7c0e4b…9ab2'],
  ['abcdefghijk', 'abcdefghijk'],
  ['abcdefghijkl', 'abcdef…ijkl'],
  ['short', 'short'],
  ['', ''],
];

test.each(shortHashCases)('shortHash(%s)', (input, output) => {
  expect(shortHash(input)).toBe(output);
});

test('hashShowsTheWholeValueInTheTitleAndACopyButton', () => {
  render(<Hash value={full} />);
  const shown = screen.getByTitle(full);
  expect(shown.textContent).toBe(shortHash(full));
  expect(screen.getByRole('button', { name: 'Copy' })).toBeTruthy();
});
