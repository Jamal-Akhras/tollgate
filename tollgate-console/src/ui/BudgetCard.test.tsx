import { act, cleanup, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { budgetsBusyFixture } from '../api/fixtures';
import type { BudgetState } from '../api/types';
import { BudgetCard, tightest } from './BudgetCard';

const send = vi.fn();

function budget(tenant: string, limit: string, spent: string): BudgetState {
  const period = 'monthly';
  return { tenant, limit, period, currency: 'USD', spent, reserved: '0', available: '0' };
}

async function show(budgets: BudgetState[]) {
  send.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(budgets))));
  const view = render(<BudgetCard />);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0);
  });
  return view;
}

beforeEach(() => {
  send.mockReset();
  vi.useFakeTimers();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

test('showsTheBudgetClosestToItsLimit', async () => {
  await show([budget('a', '100', '10'), budget('b', '50', '45'), budget('c', '10', '2')]);
  expect(screen.getByText('b · this month: $45 of $50')).toBeTruthy();
  expect(send.mock.calls[0]?.[0]).toBe('/admin/v1/budgets');
});

test('showsTheSpentAndLimitOfTheFixtureBudget', async () => {
  await show(budgetsBusyFixture);
  expect(screen.getByText('support-bot · this month: $41.18 of $150')).toBeTruthy();
});

test('showsNothingWithoutBudgets', async () => {
  const { container } = await show([]);
  expect(container.firstChild).toBeNull();
});

test('asksAgainEveryThirtySeconds', async () => {
  await show([budget('a', '100', '10')]);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_000);
  });
  expect(send).toHaveBeenCalledTimes(2);
});

test('ignoresABudgetWithNoLimit', () => {
  expect(tightest([budget('a', '0', '5'), budget('b', '10', '1')])?.tenant).toBe('b');
  expect(tightest([])).toBeNull();
});
