import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import {
  budgetsBusyFixture,
  configBusyFixture,
  configFixture,
  liveBusyFixture,
  liveFixture,
} from '../../api/fixtures';
import { parse, route } from '../../router';
import { Flow } from './Flow';

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
  expect(screen.getByText('Last 15 minutes')).toBeTruthy();
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

test('underEightHundredPixelsItListsTheSameThings', async () => {
  width = 700;
  serve(configBusyFixture, () => answer(200, liveBusyFixture));
  const { container } = render(<Flow />);
  const list = await screen.findByRole('region', { name: 'Flow as a list' });
  expect(container.querySelector('svg')).toBeNull();
  expect(within(list).getByText(/Budget: /)).toBeTruthy();
  expect(within(list).getByText(/^anthropic: /)).toBeTruthy();
  fireEvent.change(within(list).getByRole('combobox'), { target: { value: 'research' } });
  expect(location.hash).toBe('#/flow?tenant=research');
});
