import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Badge, type BadgeKind } from './Badge';

afterEach(cleanup);

test('badgeShowsItsKindAsText', () => {
  const kinds: BadgeKind[] = ['enforce', 'monitor', 'refused', 'fallback', 'dry run', 'unsealed'];
  for (const kind of kinds) {
    render(<Badge kind={kind} />);
    expect(screen.getByText(kind), kind).toBeTruthy();
    cleanup();
  }
});
