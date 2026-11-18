import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Time } from './Time';

afterEach(cleanup);

test('timePrintsHowLongAgoWithTheInstantInTheTitle', () => {
  const iso = '2026-11-16T11:57:00Z';
  render(<Time iso={iso} now={Date.parse('2026-11-16T12:00:00Z')} />);
  expect(screen.getByTitle(iso).textContent).toBe('3 min ago');
});
