import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { budgetsBusyFixture, changeFixture, configManagedFixture } from '../api/fixtures';
import type { BudgetState, ConfigView } from '../api/types';
import { Toasts } from '../ui/Toast';
import { BudgetEdit } from './BudgetEdit';

const send = vi.fn();

const config: ConfigView = {
  ...configManagedFixture,
  tenants: configManagedFixture.tenants.map((tenant) =>
    tenant.name === 'research'
      ? { ...tenant, budget: { limit: '150', currency: 'USD', period: 'monthly' } }
      : tenant,
  ),
};

const state: BudgetState = { ...budgetsBusyFixture[0]!, tenant: 'research' };

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function open(tenant: string, budget: BudgetState | null, view: ConfigView = config) {
  const onChanged = vi.fn();
  const onClose = vi.fn();
  render(
    <>
      <BudgetEdit config={view} tenant={tenant} state={budget} onChanged={onChanged} onClose={onClose} />
      <Toasts />
    </>,
  );
  return { onChanged, onClose };
}

function limit(): HTMLInputElement {
  return screen.getByLabelText('Limit') as HTMLInputElement;
}

function type(value: string) {
  fireEvent.input(limit(), { target: { value } });
}

function period(name: string) {
  fireEvent.click(screen.getByRole('tab', { name }));
}

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('theFormStartsFromTheTenantsBudgetAndSaysWhatIsTrueNow', () => {
  open('research', state);
  expect(screen.getByRole('dialog', { name: 'Budget for research' })).toBeTruthy();
  expect(limit().value).toBe('150');
  expect(limit().getAttribute('inputmode')).toBe('decimal');
  expect(screen.getByText('USD')).toBeTruthy();
  expect(screen.getByRole('tab', { name: 'Monthly' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.getByText('Spent this month: $41.18. Held for calls in flight: $0.05.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Set budget' })).toBeTruthy();
  expect(screen.queryByText(/Changing the period/)).toBeNull();
});

test('withoutABudgetTheFormIsEmptyMonthlyAndNotesTheCountStartsAtZero', () => {
  open('my-app', null);
  expect(limit().value).toBe('');
  expect(screen.getByRole('tab', { name: 'Monthly' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.getByText('No budget yet.')).toBeTruthy();
  const note = screen.getByText('The count starts at zero now. Calls made before this are not counted.');
  expect(note.closest('[role="status"]')).toBeTruthy();
  const confirm = screen.getByRole('button', { name: 'Set budget' }) as HTMLButtonElement;
  expect(confirm.disabled).toBe(true);
  type('50');
  expect(confirm.disabled).toBe(false);
  expect(document.activeElement).toBe(limit());
});

test('anInvalidLimitDisablesConfirmAndSaysWhatIsExpected', () => {
  open('research', state);
  const confirm = screen.getByRole('button', { name: 'Set budget' }) as HTMLButtonElement;
  for (const bad of ['abc', '12.', '-5', '1.1234567', '1,5']) {
    type(bad);
    expect(confirm.disabled).toBe(true);
    expect(screen.getByText('A number such as 150 or 49.50')).toBeTruthy();
  }
  for (const good of ['0', '150', '49.50', '0.000001']) {
    type(good);
    expect(confirm.disabled).toBe(false);
    expect(screen.queryByText('A number such as 150 or 49.50')).toBeNull();
  }
});

test('aLimitBelowWhatIsSpentWarnsThatCallsAreRefusedAtOnce', () => {
  open('research', state);
  type('20');
  expect(
    screen.getByText(
      'research has already spent $41.18 this month. With a limit of $20, every priced call from research is refused with budget_exceeded as soon as this is written, until the month ends.',
    ),
  ).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Set $20 anyway' })).toBeTruthy();
  period('Daily');
  expect(screen.getByText(/until the day ends\.$/)).toBeTruthy();
  period('Total');
  expect(screen.getByText(/until the limit is raised\.$/)).toBeTruthy();
  type('41.18');
  expect(screen.queryByText(/has already spent/)).toBeNull();
});

test('theBelowSpendWarningNamesTodayAndInTotalForThoseCurrentPeriods', () => {
  open('research', { ...state, period: 'daily' });
  type('1');
  expect(screen.getByText(/has already spent \$41\.18 today\./)).toBeTruthy();
  cleanup();
  open('research', { ...state, period: 'total' });
  type('1');
  expect(screen.getByText(/has already spent \$41\.18 in total\./)).toBeTruthy();
});

test('changingThePeriodWarnsAndSaysTheTrailIsSummedAgain', () => {
  open('research', state);
  period('Daily');
  const text =
    'Changing the period starts the count from zero. Tollgate sums the audit trail again for the new period when it next restarts.';
  expect(screen.getByText(text)).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Set budget' })).toBeTruthy();
  expect(screen.queryByText(/resets spend to zero/)).toBeNull();
  period('Monthly');
  expect(screen.queryByText(text)).toBeNull();
});

test('theLinesFollowTheForm', () => {
  open('research', state);
  expect(screen.getByText('tenants.research.budget')).toBeTruthy();
  expect(screen.getByText('limit: 150', { selector: '[class*=removed]' })).toBeTruthy();
  type('200');
  period('Total');
  expect(screen.getByText('limit: 200', { selector: '[class*=added]' })).toBeTruthy();
  expect(screen.getByText('period: "total"', { selector: '[class*=added]' })).toBeTruthy();
  expect(screen.getByText('period: "monthly"', { selector: '[class*=removed]' })).toBeTruthy();
});

test('confirmSendsThePathTheBodyAndTheVersion', async () => {
  send.mockResolvedValue(answer(200, changeFixture));
  const { onChanged, onClose } = open('research', state);
  type('200');
  period('Daily');
  fireEvent.click(screen.getByRole('button', { name: 'Set budget' }));
  await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  const [path, init] = send.mock.calls[0]!;
  expect(path).toBe('/admin/v1/tenants/research/budget');
  expect(init.method).toBe('PUT');
  expect(JSON.parse(init.body)).toEqual({ limit: '200', period: 'daily' });
  expect(init.headers['If-Match']).toBe(`"${config.version}"`);
  await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
});

test('cancelWritesNothing', () => {
  const { onClose } = open('research', state);
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(send).not.toHaveBeenCalled();
});
