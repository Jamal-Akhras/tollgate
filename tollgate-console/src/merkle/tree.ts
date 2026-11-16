import { node } from './proof';

export type TreeNode = {
  hash: string;
  level: number;
  left: number;
  right: number;
  start: number;
  end: number;
};

function split(size: number): number {
  let power = 1;
  while (power * 2 < size) {
    power *= 2;
  }
  return power;
}

export function shape(leafHashes: string[]): TreeNode[] {
  const nodes: TreeNode[] = leafHashes.map((hash, index) => ({
    hash,
    level: 0,
    left: -1,
    right: -1,
    start: index,
    end: index + 1,
  }));
  function build(start: number, end: number): number {
    if (end - start === 1) {
      return start;
    }
    const middle = start + split(end - start);
    const left = build(start, middle);
    const right = build(middle, end);
    nodes.push({
      hash: '',
      level: 1 + Math.max(nodes[left]?.level ?? 0, nodes[right]?.level ?? 0),
      left,
      right,
      start,
      end,
    });
    return nodes.length - 1;
  }
  if (leafHashes.length > 0) {
    build(0, leafHashes.length);
  }
  return nodes;
}

export async function levels(leafHashes: string[]): Promise<TreeNode[]> {
  const nodes = shape(leafHashes);
  for (let i = leafHashes.length; i < nodes.length; i += 1) {
    const inner = nodes[i];
    const left = nodes[inner?.left ?? -1];
    const right = nodes[inner?.right ?? -1];
    if (inner && left && right) {
      inner.hash = await node(left.hash, right.hash);
    }
  }
  return nodes;
}

export function pathNodes(size: number, index: number): number[] {
  const nodes = shape(Array.from({ length: size }, () => ''));
  const parent = new Map<number, number>();
  nodes.forEach((inner, at) => {
    if (inner.left >= 0) {
      parent.set(inner.left, at);
      parent.set(inner.right, at);
    }
  });
  const path: number[] = [];
  for (let at: number | undefined = index; at !== undefined; at = parent.get(at)) {
    if (at < 0 || at >= nodes.length) {
      return [];
    }
    path.push(at);
  }
  return path;
}
