import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { changeFixture, configFixture, configManagedFixture } from '../api/fixtures';
import type { ConfigView } from '../api/types';
import { Toasts } from '../ui/Toast';
import { GuardSwitch } from './GuardSwitch';
import { answer } from '../testing';

const send = vi.fn();

function open(config: ConfigView, policy: string, index: number, onChanged = vi.fn()) {
  render(
    <>
      <GuardSwitch config={config} policy={policy} index={index} onChanged={onChanged} />
      <Toasts />
    </>,
  );
  return onChanged;
}

function press(type: string) {
  fireEvent.click(screen.getByRole('switch', { name: `Enforce ${type}` }));
}

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('theSwitchShowsTheModeTheConfigSays', () => {
  open(configManagedFixture, 'default', 1);
  const toggle = screen.getByRole('switch', { name: 'Enforce tool-names' });
  expect(toggle.getAttribute('aria-checked')).toBe('false');
  expect(toggle.textContent).toBe('Monitoring');
  cleanup();
  open(configManagedFixture, 'default', 0);
  expect(screen.getByRole('switch', { name: 'Enforce data-class' }).textContent).toBe('Enforcing');
});

test('aGuardThatIsNotThereRendersNothing', () => {
  const { container } = render(
    <GuardSwitch config={configManagedFixture} policy="default" index={9} onChanged={vi.fn()} />,
  );
  expect(container.textContent).toBe('');
});

test('monitoringADataClassGuardWarnsWhichRegionsOpen', () => {
  open(configManagedFixture, 'strict', 0);
  press('data-class');
  expect(
    screen.getByRole('dialog', { name: 'Monitor data-class instead of enforcing it?' }),
  ).toBeTruthy();
  expect(
    screen.getByText(
      'It will still run on every call and record what it would have refused, but it will refuse nothing.',
    ),
  ).toBeTruthy();
  expect(screen.getByText('Policy strict is used by research.')).toBeTruthy();
  expect(
    screen.getByText(
      'While data-class only monitors, requests can reach regions it would refuse. For policy strict that means restricted data could go to us. Each call it would have refused is still recorded.',
    ),
  ).toBeTruthy();
  expect(screen.getByText('policies.strict.guards[0] (data-class)')).toBeTruthy();
  expect(screen.getByText('mode: "monitor"')).toBeTruthy();
  expect(screen.getByRole('button', { name: "Monitor, don't enforce" })).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
});

test('monitoringADataClassGuardSaysWhenNothingNewOpens', () => {
  open(configManagedFixture, 'default', 0);
  press('data-class');
  expect(screen.getByText('Policy default is used by my-app, support-bot.')).toBeTruthy();
  expect(
    screen.getByText(
      'While data-class only monitors, requests can reach regions it would refuse. Every provider in this config is in a region it allows, so nothing new becomes reachable today. Each call it would have refused is still recorded.',
    ),
  ).toBeTruthy();
});

test('monitoringAnotherGuardHasNoWarning', () => {
  open(configManagedFixture, 'strict', 1);
  press('request-size');
  expect(
    screen.getByRole('dialog', { name: 'Monitor request-size instead of enforcing it?' }),
  ).toBeTruthy();
  expect(screen.queryByText(/While data-class/)).toBeNull();
  expect(screen.getByRole('button', { name: 'Monitor' })).toBeTruthy();
});

test('aPolicyNoTenantUsesSaysSo', () => {
  const config = { ...configManagedFixture, tenants: [] };
  open(config, 'strict', 1);
  press('request-size');
  expect(screen.getByText('Policy strict is used by no tenant.')).toBeTruthy();
});

test('enforcingAgainAsksWithoutAWarning', () => {
  open(configManagedFixture, 'default', 1);
  press('tool-names');
  expect(screen.getByRole('dialog', { name: 'Enforce tool-names again?' })).toBeTruthy();
  expect(
    screen.getByText('Calls it would refuse are refused again as soon as this is written.'),
  ).toBeTruthy();
  expect(screen.getByText('mode: "monitor"').textContent).toBe('- removed mode: "monitor"');
  expect(screen.getByRole('button', { name: 'Enforce' })).toBeTruthy();
});

test('confirmSendsThePathTheBodyAndTheVersion', async () => {
  send.mockResolvedValue(answer(200, changeFixture));
  const onChanged = open(configManagedFixture, 'default', 1);
  press('tool-names');
  fireEvent.click(screen.getByRole('button', { name: 'Enforce' }));
  await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
  const [path, init] = send.mock.calls[0]!;
  expect(path).toBe('/admin/v1/policies/default/guards/1/mode');
  expect(init.method).toBe('PUT');
  expect(init.body).toBe('{"mode":"enforce"}');
  expect(init.headers['If-Match']).toBe(`"${configManagedFixture.version}"`);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(
    screen.getByRole('switch', { name: 'Enforce tool-names' }).getAttribute('aria-checked'),
  ).toBe('false');
});

test('cancelWritesNothing', () => {
  open(configManagedFixture, 'default', 1);
  press('tool-names');
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(send).not.toHaveBeenCalled();
});

test('withEditsOffItOpensTheYamlAndSendsNothing', () => {
  const onChanged = open(configFixture, 'default', 0);
  expect(onChanged).not.toHaveBeenCalled();
  cleanup();
  const config = { ...configManagedFixture, edits: 'read-only' as const };
  open(config, 'default', 0);
  press('data-class');
  const panel = screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' });
  expect(panel.textContent).toContain('mode: monitor');
  expect(
    screen.getByRole('switch', { name: 'Enforce data-class' }).getAttribute('aria-checked'),
  ).toBe('true');
  expect(send).not.toHaveBeenCalled();
});

test('aMovedVersionClosesTheDialogAndLoadsTheConfigAgain', async () => {
  send.mockResolvedValue(
    answer(412, { code: 'config_changed', message: 'tollgate.yaml was changed by hand' }),
  );
  const onChanged = open(configManagedFixture, 'default', 1);
  press('tool-names');
  fireEvent.click(screen.getByRole('button', { name: 'Enforce' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(await screen.findByText('tollgate.yaml was changed by hand')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledTimes(1);
});
