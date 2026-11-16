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

function canvas(onSelect = vi.fn()) {
  return render(
    <FlowCanvas
      tenants={tenants}
      policy="strict"
      checks={checksFor(configBusyFixture, 'research', [], liveBusyFixture)}
      providers={providersFor(configBusyFixture, 'research', liveBusyFixture)}
      flows={liveBusyFixture.flows.filter((flow) => flow.tenant === 'research')}
      guards={liveBusyFixture.guards.filter((guard) => guard.tenant === 'research')}
      now={Date.parse(liveBusyFixture.now)}
      onSelect={onSelect}
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
