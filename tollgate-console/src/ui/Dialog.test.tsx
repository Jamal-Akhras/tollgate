import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { useState } from 'preact/hooks';
import { afterEach, expect, test, vi } from 'vitest';
import { Dialog } from './Dialog';

afterEach(cleanup);

function Harness({ locked = false }: { locked?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOpen(true)}>
        Open it
      </button>
      {open && (
        <Dialog title="Change it?" locked={locked} onClose={() => setOpen(false)}>
          <button type="button">Cancel</button>
          <button type="button">Go</button>
        </Dialog>
      )}
    </div>
  );
}

test('isALabelledModalDialogAndMovesFocusIn', () => {
  render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: 'Open it' }));
  const dialog = screen.getByRole('dialog', { name: 'Change it?' });
  expect(dialog.getAttribute('aria-modal')).toBe('true');
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
});

test('keepsTabInsideTheDialog', () => {
  render(<Harness />);
  fireEvent.click(screen.getByRole('button', { name: 'Open it' }));
  const cancel = screen.getByRole('button', { name: 'Cancel' });
  const go = screen.getByRole('button', { name: 'Go' });
  go.focus();
  fireEvent.keyDown(document, { key: 'Tab' });
  expect(document.activeElement).toBe(cancel);
  fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
  expect(document.activeElement).toBe(go);
});

test('closesOnEscapeAndReturnsFocusToTheOpener', () => {
  render(<Harness />);
  const opener = screen.getByRole('button', { name: 'Open it' });
  opener.focus();
  fireEvent.click(opener);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.activeElement).toBe(opener);
});

test('staysOpenOnEscapeOrTheBackdropWhenLocked', () => {
  const onClose = vi.fn();
  const { container } = render(
    <Dialog title="Copy the key now" locked onClose={onClose}>
      <button type="button">Done</button>
    </Dialog>,
  );
  fireEvent.keyDown(document, { key: 'Escape' });
  fireEvent.click(container.querySelector('[aria-hidden="true"]') as HTMLElement);
  expect(onClose).not.toHaveBeenCalled();
});

test('closesOnTheBackdropWhenNotLocked', () => {
  const onClose = vi.fn();
  const { container } = render(
    <Dialog title="Change it?" onClose={onClose}>
      <button type="button">Cancel</button>
    </Dialog>,
  );
  fireEvent.click(container.querySelector('[aria-hidden="true"]') as HTMLElement);
  expect(onClose).toHaveBeenCalledTimes(1);
});
