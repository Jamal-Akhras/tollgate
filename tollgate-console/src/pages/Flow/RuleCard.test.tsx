import { cleanup, fireEvent, render, screen, within } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import { budgetsBusyFixture, configBusyFixture, liveBusyFixture } from '../../api/fixtures';
import type { ConfigView } from '../../api/types';
import { checksFor } from '../../flow/describe';
import { describeRule } from '../../flow/rule';
import { RuleCard } from './RuleCard';

afterEach(cleanup);

const checks = checksFor(configBusyFixture, 'support-bot', budgetsBusyFixture, liveBusyFixture);

function card(
  id: string,
  onChange = vi.fn(),
  onClose = vi.fn(),
  onChanged = vi.fn(),
  config: ConfigView = configBusyFixture,
) {
  const check = checks.find((item) => item.id === id)!;
  const rule = describeRule(
    configBusyFixture,
    'support-bot',
    check,
    liveBusyFixture,
    budgetsBusyFixture,
  )!;
  render(
    <RuleCard
      rule={rule}
      config={config}
      tenant="support-bot"
      guard={check.guard}
      onChange={onChange}
      onChanged={onChanged}
      onClose={onClose}
    />,
  );
  return { onChange, onClose, onChanged };
}

function row(label: string): string {
  return screen.getByText(label).parentElement!.querySelector('dd')!.textContent!;
}

test('anEnforcedGuardShowsItsTotalsSettingsAndAnEnforceSwitch', () => {
  card('guard-0');
  const region = screen.getByRole('region', { name: 'Rule data-class' });
  expect(region.textContent).toContain('495 calls in the last 15 minutes');
  expect(row('Allowed')).toBe('486');
  expect(row('Refused')).toBe('9');
  expect(row('Would have refused (monitor)')).toBe('0');
  expect(row('Regions for internal')).toBe('us, local');
  const toggle = screen.getByRole('switch', { name: 'Enforce data-class' });
  expect(toggle.getAttribute('aria-checked')).toBe('true');
  expect(within(toggle).getByText('Enforcing')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /How to switch this/ })).toBeNull();
});

test('withEditsOffTheSwitchOpensTheYamlAndStaysWhereItIs', () => {
  const { onChange } = card('guard-0');
  const toggle = screen.getByRole('switch', { name: 'Enforce data-class' });
  fireEvent.click(toggle);
  expect(
    screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' }).textContent,
  ).toContain('mode: monitor');
  expect(toggle.getAttribute('aria-checked')).toBe('true');
  expect(onChange).not.toHaveBeenCalled();
});

test('aMonitoredGuardShowsWhatItWouldHaveRefusedAndOffersToEnforce', () => {
  card('guard-1');
  expect(row('Would have refused (monitor)')).toBe('3');
  expect(row('Allowed tools')).toBe('search, lookup_order');
  expect(row('Refused tools')).toBe('shell');
  const toggle = screen.getByRole('switch', { name: 'Enforce tool-names' });
  expect(toggle.getAttribute('aria-checked')).toBe('false');
  expect(within(toggle).getByText('Monitoring')).toBeTruthy();
  fireEvent.click(toggle);
  expect(
    screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' }).textContent,
  ).toContain('mode: enforce');
});
test('aBuiltInCheckWithoutATotalSaysWhatItDoesAndAlwaysEnforces', () => {
  card('budget');
  expect(screen.getByText(/Refuses calls once the tenant has spent its limit/)).toBeTruthy();
  expect(screen.getByText('This check has no totals of its own.')).toBeTruthy();
  expect(screen.getByText('Always enforces')).toBeTruthy();
  expect(screen.queryByRole('switch')).toBeNull();
  expect(row('Limit')).toBe('$150 monthly');
  expect(screen.getByText(/Spent: \$41\.18 of \$150/)).toBeTruthy();
});

test('withEditsOffTheBudgetCardOpensTheHowToForTheBudget', () => {
  const { onChange } = card('budget');
  fireEvent.click(screen.getByRole('button', { name: 'Change budget' }));
  expect(onChange.mock.calls[0]![0].code).toContain('budget: { limit: 150, period: monthly }');
  expect(screen.queryByRole('dialog', { name: 'Budget for support-bot' })).toBeNull();
});

test('withEditsOnTheBudgetCardOpensTheBudgetForm', () => {
  const { onChange } = card('budget', vi.fn(), vi.fn(), vi.fn(), {
    ...configBusyFixture,
    edits: 'managed',
  });
  fireEvent.click(screen.getByRole('button', { name: 'Change budget' }));
  expect(screen.getByRole('dialog', { name: 'Budget for support-bot' })).toBeTruthy();
  expect((screen.getByLabelText('Limit') as HTMLInputElement).value).toBe('150');
  expect(
    screen.getByText('Spent this month: $41.18. Held for calls in flight: $0.05.'),
  ).toBeTruthy();
  expect(onChange).not.toHaveBeenCalled();
});

test('theAuditCheckShowsTheLastSealedBatch', () => {
  card('audit');
  expect(row('Mode')).toBe('async');
  expect(row('Last sealed batch')).toMatch(/^5, \d+ records$/);
});

test('theLinkGoesToActivityWithTheTenantAndTheGuard', () => {
  card('guard-0');
  expect(
    screen.getByRole('link', { name: 'See these calls in Activity' }).getAttribute('href'),
  ).toBe('#/activity?tenant=support-bot&guard=data-class');
  cleanup();
  card('budget');
  expect(
    screen.getByRole('link', { name: 'See these calls in Activity' }).getAttribute('href'),
  ).toBe('#/activity?tenant=support-bot');
});

test('escapeClosesItUnlessADialogIsOpen', () => {
  const { onClose } = card('guard-0');
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledTimes(1);
  const dialog = document.createElement('div');
  dialog.setAttribute('role', 'dialog');
  document.body.append(dialog);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledTimes(1);
  dialog.remove();
});
