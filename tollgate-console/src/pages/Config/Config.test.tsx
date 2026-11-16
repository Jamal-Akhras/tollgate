import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { configBusyFixture, configFixture } from '../../api/fixtures';
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
