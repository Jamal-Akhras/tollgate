import { useState } from 'preact/hooks';
import type { LeafView } from '../api/types';
import { pathNodes, type TreeNode } from '../merkle/tree';
import { shortHash } from './Hash';
import styles from './MerkleTree.module.css';

type MerkleTreeProps = {
  leaves: LeafView[];
  nodes: TreeNode[];
  selected?: number;
  onChoose: (leaf: LeafView) => void;
};

type Placed = { index: number; x: number; y: number; marker: boolean };

const fullLimit = 64;
const rowHeight = 44;
const margin = 24;
const spacingFull = 28;
const spacingCollapsed = 76;

const leafKinds: Record<string, string | undefined> = {
  intent: styles.intent,
  outcome: styles.outcome,
};

export function leafLabel(leaf: LeafView): string {
  return `${leaf.type} ${leaf.leafHash.slice(0, 4)}…`;
}

function visibleNodes(nodes: TreeNode[], size: number, path: number[]): Set<number> {
  const all = new Set<number>(nodes.keys());
  if (size <= fullLimit) {
    return all;
  }
  const top = nodes[nodes.length - 1]?.level ?? 0;
  const shown = new Set<number>();
  nodes.forEach((inner, index) => {
    if (inner.level >= top - 3) {
      shown.add(index);
    }
  });
  for (const index of path) {
    shown.add(index);
    const inner = nodes[index];
    if (inner && inner.left >= 0) {
      shown.add(inner.left);
      shown.add(inner.right);
    }
  }
  return shown;
}

function place(nodes: TreeNode[], shown: Set<number>, spacing: number) {
  const placed = new Map<number, Placed>();
  let next = 0;
  const top = nodes[nodes.length - 1]?.level ?? 0;
  function walk(index: number): number {
    const inner = nodes[index];
    if (!inner) {
      return 0;
    }
    const y = margin + (top - inner.level) * rowHeight;
    if (!shown.has(index) || inner.left < 0) {
      const x = margin + next * spacing;
      next += 1;
      placed.set(index, { index, x, y, marker: !shown.has(index) });
      return x;
    }
    const x = (walk(inner.left) + walk(inner.right)) / 2;
    placed.set(index, { index, x, y, marker: false });
    return x;
  }
  if (nodes.length > 0) {
    walk(nodes.length - 1);
  }
  return { placed, count: next, height: margin * 2 + top * rowHeight };
}

export function MerkleTree({ leaves, nodes, selected, onChoose }: MerkleTreeProps) {
  const [shown, setShown] = useState<string | null>(null);
  const collapsed = leaves.length > fullLimit;
  const path = selected === undefined ? [] : pathNodes(leaves.length, selected);
  const visible = visibleNodes(nodes, leaves.length, path);
  const spacing = collapsed ? spacingCollapsed : spacingFull;
  const { placed, count, height } = place(nodes, visible, spacing);
  const chain = new Set(path);
  const width = margin * 2 + Math.max(0, count - 1) * spacing;
  const root = nodes.length - 1;

  const hover = (text: string) => ({
    onMouseEnter: () => setShown(text),
    onMouseLeave: () => setShown(null),
    onFocus: () => setShown(text),
    onBlur: () => setShown(null),
  });

  const edges = [...placed.values()].flatMap((parent) => {
    const inner = nodes[parent.index];
    if (!inner || inner.left < 0 || parent.marker) {
      return [];
    }
    return [inner.left, inner.right].flatMap((child) => {
      const to = placed.get(child);
      if (!to) {
        return [];
      }
      const onPath = chain.has(parent.index) && chain.has(child);
      return [
        <line
          key={`${parent.index}-${child}`}
          class={onPath ? styles.edgeOnPath : styles.edge}
          x1={parent.x}
          y1={parent.y}
          x2={to.x}
          y2={to.y}
        />,
      ];
    });
  });

  const marks = [...placed.values()].map((spot) => {
    const inner = nodes[spot.index];
    if (!inner) {
      return null;
    }
    if (spot.marker) {
      return (
        <g key={spot.index} class={styles.marker}>
          <rect x={spot.x - 28} y={spot.y - 9} width={56} height={18} rx={4} />
          <text x={spot.x} y={spot.y + 4} text-anchor="middle">
            … {inner.end - inner.start} more
          </text>
        </g>
      );
    }
    const onPath = chain.has(spot.index);
    if (spot.index < leaves.length) {
      const leaf = leaves[spot.index];
      if (!leaf) {
        return null;
      }
      const kind = leafKinds[leaf.type];
      const known = kind !== undefined;
      const label = leafLabel(leaf);
      const pick = () => onChoose(leaf);
      return (
        <g
          key={spot.index}
          class={`${styles.leaf} ${kind ?? styles.other} ${onPath ? styles.onPath : ''}`}
          role={known ? 'button' : 'img'}
          aria-label={label}
          tabIndex={0}
          {...hover(`${label} ${inner.hash}`)}
          onClick={known ? pick : undefined}
          onKeyDown={
            known
              ? (event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    pick();
                  }
                }
              : undefined
          }
        >
          <title>{label}</title>
          <rect x={spot.x - 9} y={spot.y - 9} width={18} height={18} rx={3} />
        </g>
      );
    }
    const kind = spot.index === root ? styles.root : styles.inner;
    const body = <circle cx={spot.x} cy={spot.y} r={spot.index === root ? 9 : 6} />;
    if (!inner.hash) {
      return (
        <g key={spot.index} class={`${styles.node} ${kind}`}>
          {body}
        </g>
      );
    }
    const label = `${spot.index === root ? 'root' : 'node'} ${shortHash(inner.hash)}`;
    return (
      <g
        key={spot.index}
        class={`${styles.node} ${kind} ${onPath ? styles.onPath : ''}`}
        role="img"
        aria-label={label}
        tabIndex={0}
        {...hover(`${label} ${inner.hash}`)}
      >
        <title>{label}</title>
        {body}
      </g>
    );
  });

  return (
    <div class={styles.wrap}>
      <div class={styles.scroll}>
        <svg
          class={styles.svg}
          width={width}
          height={height}
          role="group"
          aria-label="Merkle tree of the batch"
        >
          {edges}
          {marks}
        </svg>
      </div>
      <p class={styles.caption}>{shown ?? 'Hover or focus a node to see its hash.'}</p>
    </div>
  );
}
