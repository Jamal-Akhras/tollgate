import { cleanup, fireEvent, render, screen } from '@testing-library/preact';
import { afterEach, expect, test, vi } from 'vitest';
import type { LeafView } from '../api/types';
import { levels, shape } from '../merkle/tree';
import { MerkleTree } from './MerkleTree';

afterEach(cleanup);

function hex(n: number): string {
  return n.toString(16).padEnd(64, '0');
}

function leaves(count: number): LeafView[] {
  return Array.from({ length: count }, (_, index) => ({
    index,
    type: index % 2 === 0 ? 'intent' : 'outcome',
    id: `id-${index}`,
    time: '2026-11-16T09:56:00Z',
    leafHash: hex(index + 1),
  }));
}

function renderTree(
  list: LeafView[],
  props: { selected?: number; onChoose?: (leaf: LeafView) => void } = {},
) {
  const nodes = shape(list.map((leaf) => leaf.leafHash));
  const onChoose = props.onChoose ?? (() => undefined);
  return render(
    <MerkleTree leaves={list} nodes={nodes} selected={props.selected} onChoose={onChoose} />,
  );
}

test('drawsEveryLeafOfASmallBatchAsAButtonWithItsTypeAndHash', async () => {
  const list = leaves(5);
  const nodes = await levels(list.map((leaf) => leaf.leafHash));
  render(<MerkleTree leaves={list} nodes={nodes} onChoose={() => undefined} />);
  expect(screen.getAllByRole('button')).toHaveLength(5);
  expect(screen.getByRole('button', { name: 'intent 1000…' })).toBeTruthy();
  expect(screen.getByRole('img', { name: /^root / })).toBeTruthy();
  expect(screen.getAllByRole('img', { name: /^node / })).toHaveLength(3);
});

test('choosingALeafReportsIt', async () => {
  const list = leaves(3);
  const nodes = await levels(list.map((leaf) => leaf.leafHash));
  const choose = vi.fn();
  render(<MerkleTree leaves={list} nodes={nodes} onChoose={choose} />);
  fireEvent.click(screen.getByRole('button', { name: 'outcome 2000…' }));
  fireEvent.keyDown(screen.getByRole('button', { name: 'intent 1000…' }), { key: 'Enter' });
  expect(choose.mock.calls.map((call) => (call[0] as LeafView).id)).toEqual(['id-1', 'id-0']);
});

test('showsANodesHashOnFocusAndHoverAndClearsItAfter', async () => {
  const list = leaves(2);
  const nodes = await levels(list.map((leaf) => leaf.leafHash));
  render(<MerkleTree leaves={list} nodes={nodes} onChoose={() => undefined} />);
  const root = screen.getByRole('img', { name: /^root / });
  fireEvent.focus(root);
  expect(screen.getByText(new RegExp(nodes[2]?.hash ?? 'none'))).toBeTruthy();
  fireEvent.blur(root);
  expect(screen.getByText('Hover or focus a node to see its hash.')).toBeTruthy();
  const leaf = screen.getByRole('button', { name: 'intent 1000…' });
  fireEvent.mouseEnter(leaf);
  expect(screen.getByText(new RegExp(hex(1)))).toBeTruthy();
});

test('drawsALeafOfAnUnknownTypeFromItsHashAloneWithoutALink', () => {
  const odd: LeafView = {
    index: 2,
    type: 'config-change',
    id: 'id-2',
    time: '2026-11-16T09:56:00Z',
    leafHash: 'ab12cd'.padEnd(64, '0'),
  };
  const choose = vi.fn();
  renderTree([...leaves(2), odd], { onChoose: choose });
  fireEvent.click(screen.getByRole('img', { name: 'config-change ab12…' }));
  expect(choose).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: /config-change/ })).toBeNull();
  expect(screen.getAllByRole('button')).toHaveLength(2);
});

test('collapsesAboveSixtyFourLeavesAndKeepsTheSelectedPath', () => {
  const { container } = renderTree(leaves(1000), { selected: 700 });
  const buttons = screen.getAllByRole('button');
  expect(buttons.length).toBeLessThan(40);
  expect(screen.getByRole('button', { name: 'intent 2bd0…' })).toBeTruthy();
  expect(screen.getAllByText(/… \d+ more/).length).toBeGreaterThan(1);
  expect(container.querySelectorAll('line').length).toBeGreaterThan(buttons.length);
});

test('drawsAllSixtyFourLeavesInFull', () => {
  renderTree(leaves(64));
  expect(screen.getAllByRole('button')).toHaveLength(64);
  expect(screen.queryByText(/more/)).toBeNull();
});

test('collapsesSixtyFiveLeaves', () => {
  renderTree(leaves(65));
  expect(screen.queryAllByRole('button').length).toBeLessThan(65);
});
