import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  budgetsBusyFixture,
  changeFixture,
  configBusyFixture,
  configFixture,
  configManagedFixture,
  createdKeyFixture,
} from '../../api/fixtures';
import { Toasts } from '../../ui/Toast';
import { Config } from './Config';

const send = vi.fn();

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function paths(): string[] {
  return send.mock.calls.map((call) => `${call[1].method} ${call[0]}`);
}

function open() {
  render(
    <>
      <Config />
      <Toasts />
    </>,
  );
}

beforeEach(() => {
  send.mockReset();
  vi.stubGlobal('fetch', send);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsSkeletonsUntilTheConfigArrives', async () => {
  send.mockResolvedValue(answer(200, configFixture));
  const { container } = render(<Config />);
  expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
  await screen.findByRole('table', { name: 'Tenants' });
  expect(container.querySelector('[aria-busy="true"]')).toBeNull();
});

test('rendersTheSmallConfigWithAllSixSections', async () => {
  send.mockResolvedValue(answer(200, configFixture));
  open();
  for (const name of ['Tenants', 'Keys', 'Policies', 'Providers', 'Models', 'Data classes']) {
    expect(await screen.findByRole('region', { name })).toBeTruthy();
  }
  expect(paths()).toEqual(['GET /admin/v1/config']);
  expect(screen.getByText(/Edits: off/)).toBeTruthy();
  expect(screen.getByText(/TOLLGATE_ADMIN_EDITS=file/)).toBeTruthy();
  expect(screen.getAllByRole('button', { name: /How do I change/ })).toHaveLength(6);
});

test('rendersTheBusyConfigWithAMonitoredGuard', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const policies = within(await screen.findByRole('region', { name: 'Policies' }));
  expect(policies.getAllByText('monitor')).toHaveLength(1);
  expect(policies.getAllByText('enforce')).toHaveLength(3);
  const tenants = within(screen.getByRole('region', { name: 'Tenants' }));
  expect(tenants.getByText('support-bot').getAttribute('href')).toBe('#/flow?tenant=support-bot');
});

test('saysWhenEditsAreOn', async () => {
  send.mockResolvedValue(answer(200, { ...configFixture, edits: 'managed' }));
  open();
  expect(await screen.findByText(/Edits: on/)).toBeTruthy();
  expect(screen.getByText(/keeps each version in config-history/)).toBeTruthy();
});

test('showsTheErrorWithRetry', async () => {
  send.mockResolvedValueOnce(answer(500, { code: 'boom', message: 'It broke' }));
  send.mockResolvedValueOnce(answer(200, configFixture));
  open();
  expect((await screen.findByRole('alert')).textContent).toContain('It broke');
  fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
  await screen.findByRole('table', { name: 'Tenants' });
});

test('howDoIChangeThisOpensTheYamlPanel', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const keys = within(await screen.findByRole('region', { name: 'Keys' }));
  fireEvent.click(keys.getByRole('button', { name: /How do I change/ }));
  const panel = screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' });
  expect(panel.textContent).toContain('tollgate keys create --tenant my-app');
});

test('reloadThatChangedSomethingToastsAndLoadsTheConfigAgain', async () => {
  send.mockImplementation((path: string) =>
    Promise.resolve(
      path.endsWith('/reload')
        ? answer(200, { changed: true, version: 'abcdef0123456789' })
        : answer(200, configFixture),
    ),
  );
  open();
  await screen.findByRole('table', { name: 'Tenants' });
  fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
  expect((await screen.findAllByText('Reloaded: version abcdef01…')).length).toBeGreaterThan(0);
  await waitFor(() =>
    expect(paths()).toEqual([
      'GET /admin/v1/config',
      'POST /admin/v1/config/reload',
      'GET /admin/v1/config',
    ]),
  );
});

test('reloadThatChangedNothingSaysSo', async () => {
  send.mockImplementation((path: string) =>
    Promise.resolve(
      path.endsWith('/reload')
        ? answer(200, { changed: false, version: configFixture.version })
        : answer(200, configFixture),
    ),
  );
  open();
  await screen.findByRole('table', { name: 'Tenants' });
  fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
  expect((await screen.findAllByText('Nothing changed')).length).toBeGreaterThan(0);
  expect(paths()).toEqual(['GET /admin/v1/config', 'POST /admin/v1/config/reload']);
});

test('reloadRefusedShowsTheServersMessageInABanner', async () => {
  send.mockImplementation((path: string) =>
    Promise.resolve(
      path.endsWith('/reload')
        ? answer(422, { code: 'invalid_config', message: 'tenants.x: no such policy' })
        : answer(200, configFixture),
    ),
  );
  open();
  await screen.findByRole('table', { name: 'Tenants' });
  fireEvent.click(screen.getByRole('button', { name: 'Reload' }));
  expect((await screen.findByRole('alert')).textContent).toBe('tenants.x: no such policy');
});

test('eachGuardInThePoliciesTableHasASwitchBesideItsBadge', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const policies = within(await screen.findByRole('region', { name: 'Policies' }));
  expect(policies.getAllByRole('switch')).toHaveLength(4);
  const monitored = policies.getByRole('switch', { name: 'Enforce tool-names' });
  expect(monitored.getAttribute('aria-checked')).toBe('false');
  fireEvent.click(monitored);
  expect(screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' })).toBeTruthy();
});

test('switchingAGuardWithEditsOnWritesAndShowsTheNewConfig', async () => {
  const changed = {
    ...configManagedFixture,
    version: changeFixture.version,
    policies: configManagedFixture.policies.map((policy) => ({
      ...policy,
      guards: policy.guards.map((guard) =>
        guard.type === 'tool-names' ? { ...guard, mode: 'enforce' } : guard,
      ),
    })),
  };
  let written = false;
  send.mockImplementation((path: string, init: { method: string }) => {
    if (init.method === 'PUT') {
      written = true;
      return Promise.resolve(answer(200, changeFixture));
    }
    if (path.endsWith('/budgets')) {
      return Promise.resolve(answer(200, []));
    }
    return Promise.resolve(answer(200, written ? changed : configManagedFixture));
  });
  open();
  const toggle = await screen.findByRole('switch', { name: 'Enforce tool-names' });
  fireEvent.click(toggle);
  fireEvent.click(screen.getByRole('button', { name: 'Enforce' }));
  await waitFor(() =>
    expect(
      screen.getByRole('switch', { name: 'Enforce tool-names' }).getAttribute('aria-checked'),
    ).toBe('true'),
  );
  expect(paths()).toEqual([
    'GET /admin/v1/config',
    'GET /admin/v1/budgets',
    'PUT /admin/v1/policies/default/guards/1/mode',
    'GET /admin/v1/config',
    'GET /admin/v1/budgets',
  ]);
});

test('theTenantsBudgetCellHasAChangeOrSetButton', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const tenants = within(await screen.findByRole('region', { name: 'Tenants' }));
  expect(tenants.getByRole('button', { name: 'Change budget of support-bot' }).textContent).toBe('Change');
  expect(tenants.getByRole('button', { name: 'Set a budget for my-app' }).textContent).toBe('Set a budget');
});

test('withEditsOffTheBudgetButtonOpensTheYamlPanel', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  fireEvent.click(await screen.findByRole('button', { name: 'Change budget of support-bot' }));
  const panel = screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' });
  expect(panel.textContent).toContain('budget: { limit: 150, period: monthly }');
  expect(paths()).toEqual(['GET /admin/v1/config']);
});

test('withEditsOnTheBudgetButtonOpensTheFormWithTheTenantsSpendAndWritesIt', async () => {
  const managed = { ...configBusyFixture, edits: 'managed' };
  let written = false;
  send.mockImplementation((path: string, init: { method: string }) => {
    if (init.method === 'PUT') {
      written = true;
      return Promise.resolve(answer(200, changeFixture));
    }
    return Promise.resolve(
      answer(200, path.endsWith('/budgets') ? budgetsBusyFixture : managed),
    );
  });
  open();
  fireEvent.click(await screen.findByRole('button', { name: 'Change budget of support-bot' }));
  expect(screen.getByRole('dialog', { name: 'Budget for support-bot' })).toBeTruthy();
  await screen.findByText('Spent this month: $41.18. Held for calls in flight: $0.05.');
  fireEvent.input(screen.getByLabelText('Limit'), { target: { value: '200' } });
  fireEvent.click(screen.getByRole('button', { name: 'Set budget' }));
  await waitFor(() => expect(written).toBe(true));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(paths()).toContain('PUT /admin/v1/tenants/support-bot/budget');
  await waitFor(() => expect(paths().filter((path) => path.endsWith('/budgets'))).toHaveLength(2));
});
test('withEditsOffCreateAndRevokeOpenTheYamlPanel', async () => {
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const keys = within(await screen.findByRole('region', { name: 'Keys' }));
  fireEvent.click(keys.getByRole('button', { name: 'Create a key' }));
  expect(screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' }).textContent).toContain(
    'tollgate keys create --tenant',
  );
  cleanup();
  send.mockResolvedValue(answer(200, configBusyFixture));
  open();
  const again = within(await screen.findByRole('region', { name: 'Keys' }));
  fireEvent.click(again.getAllByRole('button', { name: /^Revoke key/ })[0]!);
  const panel = screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' });
  expect(panel.textContent).toContain('Delete this line under keys');
  expect(paths().filter((path) => !path.endsWith('/config'))).toEqual([]);
});

test('withEditsOnCreatingAKeyShowsItOnceAndReloadsTheList', async () => {
  send.mockImplementation((path: string, init: { method: string }) => {
    if (init.method === 'POST') {
      return Promise.resolve(answer(200, createdKeyFixture));
    }
    return Promise.resolve(answer(200, path.endsWith('/budgets') ? [] : configManagedFixture));
  });
  open();
  const keys = within(await screen.findByRole('region', { name: 'Keys' }));
  fireEvent.click(keys.getByRole('button', { name: 'Create a key' }));
  fireEvent.change(screen.getByLabelText('Tenant'), { target: { value: 'my-app' } });
  fireEvent.click(screen.getByRole('button', { name: 'Create key' }));
  const input = (await screen.findByLabelText('API key for my-app')) as HTMLInputElement;
  expect(input.value).toBe(createdKeyFixture.key);
  fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(document.body.textContent).not.toContain(createdKeyFixture.key);
  expect(document.querySelector('input')).toBeNull();
  expect(paths()).toContain('POST /admin/v1/tenants/my-app/keys');
});

test('withEditsOnRevokingAKeyDeletesIt', async () => {
  send.mockImplementation((path: string, init: { method: string }) => {
    if (init.method === 'DELETE') {
      return Promise.resolve(answer(200, changeFixture));
    }
    return Promise.resolve(answer(200, path.endsWith('/budgets') ? [] : configManagedFixture));
  });
  open();
  fireEvent.click(await screen.findByRole('button', { name: 'Revoke key a81d07b4Xp2R' }));
  fireEvent.click(screen.getByRole('button', { name: 'Revoke key' }));
  await waitFor(() => expect(paths()).toContain('DELETE /admin/v1/keys/a81d07b4Xp2R'));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});