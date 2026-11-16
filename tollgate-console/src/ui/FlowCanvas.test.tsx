import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { configBusyFixture, liveBusyFixture } from '../api/fixtures';
import { checksFor, providersFor } from '../flow/describe';
import { FlowCanvas } from './FlowCanvas';

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

beforeEach(() => {
  width = 1200;
  vi.stubGlobal('ResizeObserver', Observer);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const tenants = [
  { name: 'support-bot', caption: '2 keys, internal', selected: false },
  { name: 'research', caption: '1 key, restricted', selected: true },
];

type Extra = { preview?: boolean; openCheck?: string | null; onCheck?: (id: string) => void };

function canvas(onSelect = vi.fn(), extra: Extra = {}) {
  return render(
    <FlowCanvas
      tenants={tenants}
      policy="strict"
      checks={checksFor(configBusyFixture, 'research', [], liveBusyFixture)}
      providers={providersFor(configBusyFixture, 'research', liveBusyFixture)}
      flows={liveBusyFixture.flows.filter((flow) => flow.tenant === 'research')}
      guards={liveBusyFixture.guards.filter((guard) => guard.tenant === 'research')}
      now={Date.parse(liveBusyFixture.now)}
      preview={extra.preview ?? false}
      openCheck={extra.openCheck ?? null}
      onCheck={extra.onCheck ?? vi.fn()}
      onSelect={onSelect}
      legend={<p>the legend</p>}
      fallback={<p>as a list</p>}
    />,
  );
}

test('drawsTheTenantsThePolicyChecksAndProviders', () => {
  canvas();
  expect(screen.getByRole('button', { name: /research/ })).toBeTruthy();
  expect(screen.getByRole('region', { name: 'Policy strict' })).toBeTruthy();
  expect(screen.getByRole('button', { name: /Personal data/ })).toBeTruthy();
  expect(screen.getByText('ollama')).toBeTruthy();
  expect(screen.queryByText('as a list')).toBeNull();
});

test('theLinesAreHiddenFromAssistiveTechnology', () => {
  const { container } = canvas();
  const lines = container.querySelector('svg[aria-hidden="true"]');
  expect(lines?.querySelectorAll('path[data-kind]').length).toBeGreaterThan(1);
});

test('aRefusalDrawsACrossAndTheWordRefused', () => {
  const { container } = canvas();
  expect(container.querySelector('[data-kind="refused"]')).toBeTruthy();
  expect(container.querySelector('svg text')?.textContent).toBe('refused');
});

test('theSelectedTenantIsPressedAndTheOthersAreOutOfTheTabOrder', () => {
  canvas();
  const research = screen.getByRole('button', { name: /research/ });
  const bot = screen.getByRole('button', { name: /support-bot/ });
  expect(research.getAttribute('aria-pressed')).toBe('true');
  expect(research.getAttribute('tabindex')).toBe('0');
  expect(bot.getAttribute('tabindex')).toBe('-1');
});

test('arrowKeysAndClicksAskForAnotherTenant', () => {
  const onSelect = vi.fn();
  canvas(onSelect);
  fireEvent.keyDown(screen.getByRole('button', { name: /research/ }), { key: 'ArrowUp' });
  expect(onSelect).toHaveBeenLastCalledWith('support-bot');
  fireEvent.keyDown(screen.getByRole('button', { name: /research/ }), { key: 'ArrowDown' });
  expect(onSelect).toHaveBeenLastCalledWith('support-bot');
  fireEvent.keyDown(screen.getByRole('button', { name: /research/ }), { key: 'a' });
  expect(onSelect).toHaveBeenCalledTimes(2);
  fireEvent.click(screen.getByRole('button', { name: /support-bot/ }));
  expect(onSelect).toHaveBeenLastCalledWith('support-bot');
});

test('underEightHundredPixelsItShowsTheFallbackAndNoDrawing', () => {
  width = 700;
  const { container } = canvas();
  expect(screen.getByText('as a list')).toBeTruthy();
  expect(container.querySelector('svg')).toBeNull();
});

test('aPillIsClickableAndReportsWhetherItsCardIsOpen', () => {
  const onCheck = vi.fn();
  canvas(vi.fn(), { onCheck, openCheck: 'guard-0' });
  const open = screen.getByRole('button', { name: /data-class/ });
  expect(open.getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByRole('button', { name: /Personal data/ }).getAttribute('aria-expanded')).toBe(
    'false',
  );
  fireEvent.click(screen.getByRole('button', { name: /Personal data/ }));
  expect(onCheck).toHaveBeenCalledWith('personal-data');
});

test('thePreviewDimsThePillsAndDrawsOneDirectLinePerProvider', () => {
  const { container } = canvas(vi.fn(), { preview: true });
  const kinds = [...container.querySelectorAll('svg path[data-kind]')].map((path) =>
    path.getAttribute('data-kind'),
  );
  expect(kinds).toEqual(['idle']);
  expect(container.firstElementChild?.className).toContain('previewing');
});

test('theLegendSitsBelowTheDrawingAndIsLeftOutOfTheList', () => {
  canvas();
  expect(screen.getByText('the legend')).toBeTruthy();
  cleanup();
  width = 700;
  canvas();
  expect(screen.queryByText('the legend')).toBeNull();
});
