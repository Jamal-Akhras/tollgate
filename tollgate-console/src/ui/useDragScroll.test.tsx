import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { useDragScroll } from './useDragScroll';

let contentHeight = 900;

class Observer {
  private readonly callback: () => void;

  constructor(callback: () => void) {
    this.callback = callback;
  }

  observe() {
    this.callback();
  }

  disconnect() {}
}

function Stage({ enabled = true, onPick }: { enabled?: boolean; onPick: () => void }) {
  const pan = useDragScroll(enabled);
  return (
    <div
      data-testid="pan"
      ref={(node) => {
        if (node) {
          Object.defineProperty(node, 'clientHeight', { value: 300, configurable: true });
          Object.defineProperty(node, 'scrollHeight', { value: contentHeight, configurable: true });
        }
        pan.ref.current = node;
      }}
      data-pannable={pan.pannable}
      data-dragging={pan.dragging}
      {...pan.handlers}
    >
      <button type="button" onClick={onPick}>
        a node
      </button>
    </div>
  );
}

beforeEach(() => {
  contentHeight = 900;
  vi.stubGlobal('ResizeObserver', Observer);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function drag(target: HTMLElement, from: number, to: number) {
  fireEvent.pointerDown(target, { button: 0, clientX: 100, clientY: from, pointerType: 'mouse' });
  fireEvent.pointerMove(target, { buttons: 1, clientX: 100, clientY: to, pointerType: 'mouse' });
  fireEvent.pointerUp(target, { clientX: 100, clientY: to, pointerType: 'mouse' });
}

test('draggingMovesTheContentWhenItIsLargerThanItsFrame', () => {
  render(<Stage onPick={vi.fn()} />);
  const pan = screen.getByTestId('pan');
  expect(pan.dataset.pannable).toBe('true');
  drag(pan, 200, 80);
  expect(pan.scrollTop).toBe(120);
  expect(pan.dataset.dragging).toBe('false');
});

test('aDragDoesNotClickTheNodeUnderIt', () => {
  const onPick = vi.fn();
  render(<Stage onPick={onPick} />);
  const node = screen.getByRole('button', { name: 'a node' });
  drag(node, 200, 80);
  fireEvent.click(node);
  expect(onPick).not.toHaveBeenCalled();
  fireEvent.click(node);
  expect(onPick).toHaveBeenCalledTimes(1);
});

test('aPressThatBarelyMovesIsStillAClick', () => {
  const onPick = vi.fn();
  render(<Stage onPick={onPick} />);
  const node = screen.getByRole('button', { name: 'a node' });
  drag(node, 200, 198);
  fireEvent.click(node);
  expect(onPick).toHaveBeenCalledTimes(1);
  expect(screen.getByTestId('pan').scrollTop).toBe(0);
});

test('aDragThatEndsOutsideIsForgottenAndDoesNotEatTheNextClick', () => {
  const onPick = vi.fn();
  render(<Stage onPick={onPick} />);
  const pan = screen.getByTestId('pan');
  const node = screen.getByRole('button', { name: 'a node' });
  fireEvent.pointerDown(pan, { button: 0, clientX: 100, clientY: 200, pointerType: 'mouse' });
  fireEvent.pointerMove(pan, { buttons: 0, clientX: 100, clientY: 80, pointerType: 'mouse' });
  expect(pan.scrollTop).toBe(0);
  drag(pan, 200, 80);
  fireEvent.pointerDown(node, { button: 0, clientX: 100, clientY: 100, pointerType: 'mouse' });
  fireEvent.pointerUp(node, { clientX: 100, clientY: 100, pointerType: 'mouse' });
  fireEvent.click(node);
  expect(onPick).toHaveBeenCalledTimes(1);
});

test('contentThatFitsIsNotDraggable', () => {
  contentHeight = 300;
  render(<Stage onPick={vi.fn()} />);
  const pan = screen.getByTestId('pan');
  expect(pan.dataset.pannable).toBe('false');
  drag(pan, 200, 80);
  expect(pan.scrollTop).toBe(0);
});

test('nothingIsDraggableWhenItIsSwitchedOff', () => {
  render(<Stage enabled={false} onPick={vi.fn()} />);
  expect(screen.getByTestId('pan').dataset.pannable).toBe('false');
});
