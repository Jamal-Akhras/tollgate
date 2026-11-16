import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Card } from './Card';

afterEach(cleanup);

test('cardShowsItsChildren', () => {
  render(<Card>Inside</Card>);
  expect(screen.getByText('Inside')).toBeTruthy();
});

test('dashedCardGetsTheDashedClass', () => {
  const plain = render(<Card>Plain</Card>).getByText('Plain');
  const dashed = render(<Card variant="dashed">Dashed</Card>).getByText('Dashed');
  expect(dashed.className).not.toBe(plain.className);
});
