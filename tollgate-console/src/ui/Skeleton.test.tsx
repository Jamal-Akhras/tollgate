import { cleanup, render } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { Skeleton } from './Skeleton';

afterEach(cleanup);

test('skeletonIsHiddenFromAssistiveTechnologyAndTakesItsSize', () => {
  const { container } = render(<Skeleton width="120px" height="24px" />);
  const block = container.firstElementChild as HTMLElement;
  expect(block.getAttribute('aria-hidden')).toBe('true');
  expect(block.style.width).toBe('120px');
  expect(block.style.height).toBe('24px');
});
