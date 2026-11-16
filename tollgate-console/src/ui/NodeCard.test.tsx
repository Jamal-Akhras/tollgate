import { cleanup, render, screen } from '@testing-library/preact';
import { afterEach, expect, test } from 'vitest';
import { NodeCard } from './NodeCard';

afterEach(cleanup);

test('nodeCardShowsTitleAndCaption', () => {
  render(<NodeCard icon={<i />} title="Gateway" caption="Port 8080" />);
  expect(screen.getByText('Gateway')).toBeTruthy();
  expect(screen.getByText('Port 8080')).toBeTruthy();
  expect(screen.queryByTestId('stub')).toBeNull();
});

test('nodeCardDrawsAConnectorStubOnTheGivenSide', () => {
  render(<NodeCard icon={<i />} title="Gateway" caption="Port 8080" stub="left" />);
  expect(screen.getByTestId('stub')).toBeTruthy();
});

test('nodeCardCanHaveADashedOutline', () => {
  const { container } = render(<NodeCard icon={<i />} title="Gateway" caption="Port 8080" dashed />);
  expect(container.firstElementChild?.className).toContain('dashed');
});
