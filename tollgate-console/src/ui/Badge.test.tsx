import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Badge, type BadgeKind } from './Badge';

afterEach(cleanup);

const kinds: BadgeKind[] = [
  'enforce',
  'monitor',
  'refused',
  'fallback',
  'dry run',
  'unsealed',
  'sent',
  'waiting',
  'failed',
];

test.each(kinds)('badgeShowsItsKindAsText(%s)', (kind) => {
  render(<Badge kind={kind} />);
  expect(screen.getByText(kind)).toBeTruthy();
});
