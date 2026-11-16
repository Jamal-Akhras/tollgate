import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  budgetsBusyFixture,
  changeFixture,
  configBusyFixture,
  configFixture,
  configManagedFixture,
  liveBusyFixture,
  liveFixture,
} from '../../api/fixtures';
import { parse, route } from '../../router';
import { Flow } from './Flow';
import { tollgateOn } from './Preview';

const send = vi.fn();
let width = 1200;

type Entries = { contentRect: { width: number } }[];

class Observer {
  private readonly callback: (entries: Entries) => void;

  constructor(callback: (entries: Entries) => void) {
    this.callback = callback;
  }

  observe() {
    this.callback([{ contentRect: { width } }]);
  }

  disconnect() {}
}

function answer(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status });
}

function serve(config: unknown, live: () => Response, budgets: unknown = []) {
  send.mockImplementation((path: string) => {
    if (path.includes('/live')) {
      return Promise.resolve(live());
    }
    return Promise.resolve(answer(200, path.includes('/budgets') ? budgets : config));
  });
}

beforeEach(() => {
  send.mockReset();
  width = 1200;
  location.hash = '#/flow';
  route.value = parse('#/flow');
  vi.stubGlobal('fetch', send);
  vi.stubGlobal('ResizeObserver', Observer);
  tollgateOn.value = true;
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

test('showsSkeletonsUntilTheConfigArrives', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  const { container } = render(<Flow />);
  expect(container.querySelector('[aria-busy="true"]')).toBeTruthy();
  await screen.findByRole('region', { name: 'Policy default' });
  expect(container.querySelector('[aria-busy="true"]')).toBeNull();
});

test('rendersTheBusyFixturesForTheBusiestTenant', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture), budgetsBusyFixture);
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  expect(screen.getByRole('heading', { level: 1, name: 'Flow' })).toBeTruthy();
  expect(screen.getAllByText('Last 15 minutes').length).toBeGreaterThan(0);
  await screen.findByText('Live');
  expect(screen.getByRole('button', { name: /support-bot/ }).getAttribute('aria-pressed')).toBe(
    'true',
  );
  for (const label of ['Request review', 'data-class', 'tool-names', 'Budget', 'Rate limits']) {
    expect(screen.getByRole('button', { name: new RegExp(label) })).toBeTruthy();
  }
  await waitFor(() => expect(screen.getByText(/\$41\.18 \/ \$150/)).toBeTruthy());
  expect(screen.getByText('anthropic')).toBeTruthy();
  expect(screen.getByText('openai')).toBeTruthy();
});

test('pollsTheConfigOnceTheLiveViewForFifteenMinutesAndTheBudgets', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  const paths = send.mock.calls.map((call) => call[0]);
  expect(paths).toContain('/admin/v1/config');
  expect(paths).toContain('/admin/v1/live?minutes=15');
  expect(paths).toContain('/admin/v1/budgets');
  expect(paths.filter((path) => path === '/admin/v1/config')).toHaveLength(1);
});

test('rendersTheSmallConfigFromTheBaseFixtures', async () => {
  serve(configFixture, () => answer(200, liveFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  expect(screen.getByRole('button', { name: /my-app/ })).toBeTruthy();
  expect(screen.getByRole('button', { name: /Signed audit trail/ }).textContent).toContain(
    'batch 1',
  );
});

test('theTenantInTheUrlIsSelected', async () => {
  route.value = parse('#/flow?tenant=research');
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy strict' });
  expect(screen.getByRole('button', { name: /research/ }).getAttribute('aria-pressed')).toBe(
    'true',
  );
});

test('anUnknownTenantInTheUrlFallsBackToTheBusiest', async () => {
  route.value = parse('#/flow?tenant=nobody');
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  expect(screen.getByRole('button', { name: /support-bot/ }).getAttribute('aria-pressed')).toBe(
    'true',
  );
});

test('arrowKeysMoveTheSelectionAndPutItInTheUrl', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  fireEvent.keyDown(screen.getByRole('button', { name: /support-bot/ }), { key: 'ArrowDown' });
  expect(location.hash).toBe('#/flow?tenant=research');
  route.value = parse(location.hash);
  await screen.findByRole('region', { name: 'Policy strict' });
  const research = screen.getByRole('button', { name: /research/ });
  expect(research.getAttribute('aria-pressed')).toBe('true');
  expect(document.activeElement).toBe(research);
});

test('withTheAuditTrailOffTheDiagramStillDrawsWithABanner', async () => {
  serve(configBusyFixture, () =>
    answer(409, { code: 'audit_off', message: 'The audit trail is off' }),
  );
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  await screen.findByText(
    'The audit trail is off in tollgate.yaml, so there is nothing to show here',
  );
  expect(screen.queryByText('Live')).toBeNull();
  expect(screen.queryByText(/Retrying/)).toBeNull();
  expect(screen.getByRole('button', { name: /support-bot/ })).toBeTruthy();
});

test('saysSoWhenTheLiveViewSkippedRecords', async () => {
  serve(configBusyFixture, () => answer(200, { ...liveBusyFixture, skipped: true }));
  render(<Flow />);
  await screen.findByText('Busy: the live view skipped some records');
});

test('showsTheConfigError', async () => {
  send.mockResolvedValue(answer(500, { code: 'boom', message: 'It broke' }));
  render(<Flow />);
  expect((await screen.findByRole('alert')).textContent).toContain('It broke');
  expect(screen.getByRole('button', { name: 'Retry' })).toBeTruthy();
});

test('underEightHundredPixelsItShowsTheSameThingsAsTables', async () => {
  width = 700;
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  const { container } = render(<Flow />);
  const checks = await screen.findByRole('table', { name: 'Checks for support-bot' });
  expect(container.querySelector('svg path[data-kind]')).toBeNull();
  expect(within(checks).getByText('Budget')).toBeTruthy();
  expect(
    within(screen.getByRole('table', { name: 'Providers for support-bot' })).getByText(/^anthropic$/),
  ).toBeTruthy();
  fireEvent.change(screen.getByRole('combobox'), { target: { value: 'research' } });
  expect(location.hash).toBe('#/flow?tenant=research');
});

test('showAsTableSwapsTheDiagramForARowPerFlow', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  fireEvent.click(screen.getByRole('button', { name: 'Show as table' }));
  expect(screen.queryByRole('region', { name: 'Policy default' })).toBeNull();
  const table = screen.getByRole('table', { name: 'Calls by tenant, model and provider' });
  expect(within(table).getAllByRole('row')).toHaveLength(liveBusyFixture.flows.length + 1);
  fireEvent.click(screen.getByRole('button', { name: 'Show as diagram' }));
  expect(screen.getByRole('region', { name: 'Policy default' })).toBeTruthy();
});

test('aPillOpensItsRuleCardAndASecondClickOrEscapeClosesIt', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture), budgetsBusyFixture);
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  const pill = screen.getByRole('button', { name: /data-class/ });
  expect(pill.getAttribute('aria-expanded')).toBe('false');
  fireEvent.click(pill);
  expect(pill.getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByRole('region', { name: 'Rule data-class' })).toBeTruthy();
  fireEvent.click(pill);
  expect(screen.queryByRole('region', { name: 'Rule data-class' })).toBeNull();
  fireEvent.click(pill);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('region', { name: 'Rule data-class' })).toBeNull();
  expect(pill.getAttribute('aria-expanded')).toBe('false');
});

test('theRuleCardSwitchOffersTheHowToPanelWithEditsOff', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture), budgetsBusyFixture);
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  fireEvent.click(screen.getByRole('button', { name: /tool-names/ }));
  fireEvent.click(screen.getByRole('switch', { name: 'Enforce tool-names' }));
  expect(screen.getByRole('dialog', { name: 'Change this in tollgate.yaml' })).toBeTruthy();
  expect(screen.getByText(/mode: enforce/)).toBeTruthy();
});

test('theRuleCardSwitchWritesWithEditsOnAndFollowsTheNewConfig', async () => {
  const managed = { ...configManagedFixture };
  const enforced = {
    ...managed,
    version: changeFixture.version,
    policies: managed.policies.map((policy) => ({
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
    if (path.includes('/live')) {
      return Promise.resolve(answer(200, liveBusyFixture));
    }
    if (path.includes('/budgets')) {
      return Promise.resolve(answer(200, budgetsBusyFixture));
    }
    return Promise.resolve(answer(200, written ? enforced : managed));
  });
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  fireEvent.click(screen.getByRole('button', { name: /tool-names/ }));
  fireEvent.click(screen.getByRole('switch', { name: 'Enforce tool-names' }));
  fireEvent.click(screen.getByRole('button', { name: 'Enforce' }));
  await waitFor(() =>
    expect(
      screen.getByRole('switch', { name: 'Enforce tool-names' }).getAttribute('aria-checked'),
    ).toBe('true'),
  );
});
test('theCardClosesWhenAnotherTenantIsChosen', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  fireEvent.click(screen.getByRole('button', { name: /data-class/ }));
  expect(screen.getByRole('region', { name: 'Rule data-class' })).toBeTruthy();
  route.value = parse('#/flow?tenant=research');
  await screen.findByRole('region', { name: 'Policy strict' });
  expect(screen.queryByRole('region', { name: /^Rule / })).toBeNull();
});

test('withThePreviewOffEveryProviderGetsAGreyLineAndTheNoteAppears', async () => {
  tollgateOn.value = false;
  location.hash = '#/flow?tenant=research';
  route.value = parse(location.hash);
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  const { container, unmount } = render(<Flow />);
  await screen.findByRole('region', { name: 'Policy strict' });
  const lines = container.querySelectorAll('svg path[data-kind]');
  expect(lines).toHaveLength(configBusyFixture.providers.length);
  expect([...lines].every((line) => line.getAttribute('data-kind') === 'idle')).toBe(true);
  expect(screen.getByText('Without Tollgate.')).toBeTruthy();
  expect(screen.getByText('Without Tollgate.').parentElement?.textContent).toContain(
    'anthropic in us and openai in us would be reachable. This is a preview; nothing has changed.',
  );
  expect(screen.getAllByText(/us · reachable directly/)).toHaveLength(2);
  unmount();
  expect(tollgateOn.value).toBe(true);
});

test('fourTilesLinkToMetricsAndNameTheirWindows', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  await screen.findByText('Calls per minute');
  const links = screen.getAllByRole('link').filter((link) => link.getAttribute('href') === '#/metrics');
  expect(links).toHaveLength(4);
  expect(links.map((link) => link.textContent).join(' ')).toMatch(/Last minute.*Last 15 minutes.*Last 15 minutes.*Last 5 minutes/);
});

test('aTenantWithNoFlowGetsTheFirstRunPanelUntilItsFirstCall', async () => {
  const quiet = { ...liveBusyFixture, flows: [], guards: [] };
  serve(configBusyFixture, () => answer(200, quiet));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  await screen.findByRole('region', { name: 'First call' });
  expect(screen.getByText(/v1\/chat\/completions/)).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: /data-class/ }));
  expect(screen.queryByRole('region', { name: 'First call' })).toBeNull();
});

test('theBusiestTenantHasNoFirstRunPanel', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  await screen.findByText('Calls per minute');
  expect(screen.queryByRole('region', { name: 'First call' })).toBeNull();
});

test('moreThanEightTenantsGetAFilterBox', async () => {
  const template = configBusyFixture.tenants[0]!;
  const many = {
    ...configBusyFixture,
    tenants: Array.from({ length: 12 }, (_, index) => ({ ...template, name: `team-${index + 1}` })),
  };
  serve(many, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  expect(screen.getAllByRole('button', { name: /team-/ })).toHaveLength(12);
  fireEvent.input(screen.getByRole('searchbox', { name: 'Filter tenants' }), {
    target: { value: 'team-12' },
  });
  const left = screen.getAllByRole('button', { name: /team-/ });
  expect(left.map((button) => button.getAttribute('data-tenant'))).toEqual(['team-1', 'team-12']);
});

test('eightTenantsOrFewerHaveNoFilterBox', async () => {
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  render(<Flow />);
  await screen.findByRole('region', { name: 'Policy default' });
  expect(screen.queryByRole('searchbox')).toBeNull();
});
