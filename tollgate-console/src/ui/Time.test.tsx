import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Time } from './Time';

afterEach(cleanup);

const now = Date.parse('2026-11-16T12:00:00Z');

test('timePrintsHowLongAgoWithTheInstantInTheTitle', () => {
  const table: [string, string][] = [
    ['2026-11-16T12:00:00Z', 'just now'],
    ['2026-11-16T11:59:48Z', '12 s ago'],
    ['2026-11-16T11:57:00Z', '3 min ago'],
    ['2026-11-16T10:00:00Z', '2 h ago'],
    ['2026-11-10T10:00:00Z', '2026-11-10'],
  ];
  for (const [iso, text] of table) {
    render(<Time iso={iso} now={now} />);
    const element = screen.getByTitle(iso);
    expect(element.textContent, iso).toBe(text);
    cleanup();
  }
});
