import { expect, test } from 'vitest';
import { driverRows } from './grouping';
import { flow, snapshot } from './snapshots';

test('groupsFlowsByTenantMostCallsFirstWithFailureAndRefusalCounts', () => {
  const live = snapshot({
    flows: [
      flow({ tenant: 'a', sent: 20, failed: 2, serverErrors: 2, refused: { policy_denied: 6 } }),
      flow({ tenant: 'a', model: 'b', sent: 10, cost: { USD: '0.1' }, inputTokens: 5 }),
      flow({ tenant: 'c', sent: 5, cost: { USD: '0.25' }, outputTokens: 3 }),
    ],
    latency: { providers: {}, tenants: { a: { p50Millis: 400, p95Millis: 900 } }, models: {} },
  });
  const rows = driverRows(live, 'tenant');
  expect(rows.map((row) => row.name)).toEqual(['a', 'c']);
  expect(rows[0]).toMatchObject({
    calls: 38,
    refused: 6,
    inputTokens: 5,
    cost: { USD: '0.1' },
    p95Millis: 900,
  });
  expect(rows[0]?.failedShare).toBeCloseTo(10.5, 1);
  expect(rows[1]?.p95Millis).toBeNull();
});

test('groupsFlowsByModelAndReadsTheModelPercentiles', () => {
  const live = snapshot({
    flows: [flow({ tenant: 'a', model: 'm', sent: 30 }), flow({ tenant: 'b', model: 'm', sent: 5 })],
    latency: { providers: {}, tenants: {}, models: { m: { p50Millis: 1, p95Millis: 700 } } },
  });
  const rows = driverRows(live, 'model');
  expect(rows).toHaveLength(1);
  expect(rows[0]).toMatchObject({ name: 'm', calls: 35, p95Millis: 700 });
});

test('keepsTheTopTenAndSumsTheRestAsOther', () => {
  const flows = Array.from({ length: 13 }, (_, index) =>
    flow({ tenant: `t${String(index).padStart(2, '0')}`, sent: 100 - index, cost: { USD: '1.5' } }),
  );
  const rows = driverRows(snapshot({ flows }), 'tenant');
  expect(rows).toHaveLength(11);
  expect(rows[9]?.name).toBe('t09');
  const other = rows[10];
  expect(other).toMatchObject({ name: 'Other', other: true, calls: 90 + 89 + 88, p95Millis: null });
  expect(other?.cost).toEqual({ USD: '4.5' });
});

test('hasNoOtherRowForTenOrFewer', () => {
  const flows = Array.from({ length: 10 }, (_, index) => flow({ tenant: `t${index}`, sent: 1 }));
  expect(driverRows(snapshot({ flows }), 'tenant').some((row) => row.other)).toBe(false);
});

test('showsZeroSpendInTheCurrencyOtherRowsUseAndSkipsClientClosures', () => {
  const live = snapshot({
    flows: [
      flow({ tenant: 'a', sent: 10, cost: { USD: '1.5' } }),
      flow({ tenant: 'b', sent: 10, failed: 4, clientClosed: 4 }),
    ],
  });
  const rows = driverRows(live, 'tenant');
  expect(rows.map((row) => row.cost)).toEqual([{ USD: '0' }, { USD: '1.5' }]);
  expect(rows[0]?.failedShare).toBe(0);
});
