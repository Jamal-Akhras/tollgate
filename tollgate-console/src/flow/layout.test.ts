import { expect, test } from 'vitest';
import { layout, type Layout, type Rect } from './layout';

function overlap(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

function inside(inner: Rect, outer: Rect): boolean {
  return (
    inner.x >= outer.x &&
    inner.y >= outer.y &&
    inner.x + inner.w <= outer.x + outer.w &&
    inner.y + inner.h <= outer.y + outer.h
  );
}

function topLevel(result: Layout): Rect[] {
  return [...result.tenants, result.policy, ...result.providers];
}

const widths = [800, 1000, 1199, 1200, 1600];
const counts = [
  { tenants: 1, checks: 3, providers: 1 },
  { tenants: 3, checks: 6, providers: 3 },
  { tenants: 12, checks: 8, providers: 5 },
];

test('underEightHundredPixelsThereIsNoLayout', () => {
  expect(layout(799, counts[1]!)).toBeNull();
  expect(layout(0, counts[0]!)).toBeNull();
});

test('theBandFollowsTheMeasuredWidth', () => {
  expect(layout(800, counts[0]!)?.band).toBe('medium');
  expect(layout(1199, counts[0]!)?.band).toBe('medium');
  expect(layout(1200, counts[0]!)?.band).toBe('wide');
});

test('mediumCardsAreTwoHundredTwoHundredFortyAndTwoHundred', () => {
  const result = layout(900, counts[1]!)!;
  expect([result.tenants[0]!.w, result.policy.w, result.providers[0]!.w]).toEqual([200, 240, 200]);
});

test('nothingOverlapsAndEverythingFitsTheWidthInEveryBand', () => {
  for (const width of widths) {
    for (const count of counts) {
      const result = layout(width, count)!;
      const rects = topLevel(result);
      expect(result.tenants).toHaveLength(count.tenants);
      expect(result.pills).toHaveLength(count.checks);
      expect(result.providers).toHaveLength(count.providers);
      for (const rect of [...rects, ...result.pills]) {
        expect(rect.x).toBeGreaterThanOrEqual(0);
        expect(rect.x + rect.w).toBeLessThanOrEqual(width);
        expect(rect.y + rect.h).toBeLessThanOrEqual(result.height);
      }
      rects.forEach((rect, index) => {
        rects.slice(index + 1).forEach((other) => expect(overlap(rect, other)).toBe(false));
      });
      result.pills.forEach((pill, index) => {
        expect(inside(pill, result.policy)).toBe(true);
        result.pills.slice(index + 1).forEach((other) => expect(overlap(pill, other)).toBe(false));
      });
    }
  }
});

test('columnsRunLeftToRight', () => {
  const result = layout(1400, counts[1]!)!;
  expect(result.tenants[0]!.x + result.tenants[0]!.w).toBeLessThan(result.policy.x);
  expect(result.policy.x + result.policy.w).toBeLessThan(result.providers[0]!.x);
});

test('noTenantsOrProvidersStillLaysOutThePolicyBox', () => {
  const result = layout(1000, { tenants: 0, checks: 0, providers: 0 })!;
  expect(result.tenants).toEqual([]);
  expect(result.providers).toEqual([]);
  expect(result.policy.h).toBeGreaterThan(0);
});
