import { expect, test } from 'vitest';
import type { BudgetState } from '../../api/types';
import { budgetRows, reachText } from './burn';
import { flow, snapshot, totals } from './snapshots';

function budget(tenant: string, spent: string, rest: Partial<BudgetState> = {}): BudgetState {
  return {
    tenant,
    limit: '100',
    period: 'monthly',
    currency: 'USD',
    spent,
    reserved: '0',
    available: String(100 - Number(spent)),
    ...rest,
  };
}

test('namesTheTimeInMinutesUnderAnHourHoursUnderTwoDaysAndDaysBeyond', () => {
  expect(reachText(0.5)).toBe('30 min');
  expect(reachText(0.001)).toBe('1 min');
  expect(reachText(3)).toBe('3 h');
  expect(reachText(47.4)).toBe('47 h');
  expect(reachText(48)).toBe('2 days');
  expect(reachText(240)).toBe('10 days');
});

test('sortsBudgetsByShareUsedMostFirst', () => {
  const all = [budget('a', '10'), budget('b', '90'), budget('c', '50')];
  const rows = budgetRows(all, snapshot());
  expect(rows.map((row) => row.budget.tenant)).toEqual(['b', 'c', 'a']);
  expect(rows[0]?.used).toBe(0.9);
});

test('doesNotDivideByALimitOfZero', () => {
  const rows = budgetRows([budget('a', '5', { limit: '0', available: '0' })], snapshot());
  expect(rows[0]?.used).toBe(0);
});

test('scalesTheTenantsSpendInTheWindowToAnHourAndSaysWhenItReachesTheLimit', () => {
  const live = snapshot({
    flows: [
      flow({ tenant: 'a', cost: { USD: '0.5' } }),
      flow({ tenant: 'a', model: 'b', cost: { USD: '0.5' } }),
      flow({ tenant: 'other', cost: { USD: '9' } }),
    ],
  });
  const [row] = budgetRows([budget('a', '90', { available: '9' })], live);
  expect(row?.perHour).toBe(4);
  expect(row?.reach).toBe('2 h');
});

test('hasNoReachWhenThereIsNoSpendOrNothingIsLeft', () => {
  const live = snapshot({ flows: [flow({ tenant: 'a', cost: { USD: '1' } })] });
  const quiet = budgetRows([budget('b', '1')], live);
  expect(quiet[0]?.perHour).toBe(0);
  expect(quiet[0]?.reach).toBeNull();
  const spent = budgetRows([budget('a', '100', { available: '0' })], live);
  expect(spent[0]?.perHour).toBe(4);
  expect(spent[0]?.reach).toBeNull();
});

test('ignoresSpendInAnotherCurrency', () => {
  const live = snapshot({ flows: [flow({ tenant: 'a', cost: { EUR: '5' } })] });
  expect(budgetRows([budget('a', '1')], live)[0]?.perHour).toBe(0);
});

test('scalesByTheSecondsTheWindowActuallyCoversAndNeverByZero', () => {
  const flows = [flow({ tenant: 'a', cost: { USD: '1' } })];
  const short = snapshot({ flows, totals: totals({ seconds: 450 }) });
  expect(budgetRows([budget('a', '1')], short)[0]?.perHour).toBe(8);
  const none = snapshot({ flows, totals: totals({ seconds: 0 }) });
  expect(budgetRows([budget('a', '1')], none)[0]?.perHour).toBe(0);
});
