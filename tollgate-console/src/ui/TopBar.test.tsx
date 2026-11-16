import { act, cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { tollgateOn } from '../pages/Flow/Preview';
import { parse, route } from '../router';
import { TopBar } from './TopBar';

afterEach(() => {
  cleanup();
  tollgateOn.value = true;
});

function bar() {
  return render(
    <TopBar
      version="0.2.0"
      narrow={false}
      collapsed={false}
      drawerOpen={false}
      onToggle={() => undefined}
      toggleRef={{ current: null }}
    />,
  );
}

test('theFlowPageHasALabelledPreviewSwitchThatStartsOn', () => {
  route.value = parse('#/flow');
  bar();
  const toggle = screen.getByRole('switch', { name: 'Preview this page without Tollgate' });
  expect(toggle.getAttribute('aria-checked')).toBe('true');
  expect(screen.getByText('Preview')).toBeTruthy();
  fireEvent.click(toggle);
  expect(tollgateOn.value).toBe(false);
  expect(toggle.getAttribute('aria-checked')).toBe('false');
  expect(toggle.textContent).toContain('Off');
});

test('otherPagesHaveNoPreviewSwitch', () => {
  route.value = parse('#/usage');
  bar();
  expect(screen.queryByRole('switch')).toBeNull();
  act(() => {
    route.value = parse('#/flow');
  });
  expect(screen.getByRole('switch')).toBeTruthy();
});
