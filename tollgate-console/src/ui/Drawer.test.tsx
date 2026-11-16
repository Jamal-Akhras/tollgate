import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { useState } from 'preact/hooks';
import { afterEach, expect, test, vi } from 'vitest';
import { Drawer } from './Drawer';

afterEach(cleanup);

function Harness() {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Open call
      </button>
      {open && (
        <Drawer title="Call 01KA" onClose={() => setOpen(false)}>
          <a href="#/audit">Audit</a>
          <button type="button">Copy</button>
        </Drawer>
      )}
    </div>
  );
}

test('isALabelledDialogAndMovesFocusIn', () => {
  render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: 'Open call' }));
  const dialog = screen.getByRole('dialog', { name: 'Call 01KA' });
  expect(dialog.getAttribute('aria-modal')).toBe('true');
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
});

test('keepsTabInsideTheDrawer', () => {
  render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: 'Open call' }));
  const close = screen.getByRole('button', { name: 'Close' });
  const copy = screen.getByRole('button', { name: 'Copy' });
  copy.focus();
  fireEvent.keyDown(document, { key: 'Tab' });
  expect(document.activeElement).toBe(close);
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
  expect(document.activeElement).toBe(copy);
});

test('closesOnEscapeAndReturnsFocusToTheOpener', () => {
  render(<Harness />);
  const opener = screen.getByRole('button', { name: 'Open call' });
  opener.focus();
  fireEvent.click(opener);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.activeElement).toBe(opener);
});

test('closesWithTheCloseButtonAndTheBackdrop', () => {
  const onClose = vi.fn();
  const { container } = render(
    <Drawer title="Record" onClose={onClose}>
      text
    </Drawer>,
  );
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  expect(onClose).toHaveBeenCalledTimes(1);
  fireEvent.click(container.querySelector('[aria-hidden="true"]') as HTMLElement);
  expect(onClose).toHaveBeenCalledTimes(2);
});
