import { expect, test } from 'vitest';
import type { ProofView } from '../api/types';
import { canHash, leafHash, node, verifyProof } from './proof';
import { levels, pathNodes, shape } from './tree';

const pinned: ProofView = {
  index: 5,
  size: 7,
  encoding:
    'AQEAAAAJcmVxdWVzdC01AAAAAGromB4ehIAAAAAAC3N1cHBvcnQtYm90AAAADGs3UW0yeFBhNGJacgAAAAlhbnRocm9waWMAAAANY2xhdWRlLXNvbm5ldAAAAAlhbnRocm9waWMAAAARY2xhdWRlLXNvbm5ldC00LTUBAAAACGludGVybmFsPxwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAmrKpGxEREREREREREREREREREREREREREREREREREREREQAAAAIAAAAMcmVxdWVzdC1zaXplAAAAAAAAAAg0AAAACmRhdGEtY2xhc3MAAAAAAAAADUgAAQAAAANVU0QAAAAACRPp4A==',
  leafHash: 'e2c803413dd1a9c780824d310c709f28c7c320d9b7d025c6b61c536549d98b8b',
  path: [
    'cf3ddaa573d32b552d74ad0023e1e2ccd1fbc8f8895be3bade414e8c4996ffaa',
    '83218bbb8c80af13da576e5b5ed7848bc0e6edcd8814076838704c0096b6f572',
    '95583037b5e3ccf1034b20f31d62d5ee6fd70f16c24a9997d5abd12e2c143392',
  ],
  root: '087488b9fe80236f80e48b029a1b678a3b9c86956a0566a0f474ca6e996f23f7',
};

function base64(bytes: number[]): string {
  return btoa(String.fromCharCode(...bytes));
}

function run(first: number, count: number): number[] {
  return Array.from({ length: count }, (_, i) => first + i);
}

const rfcLeaves = [
  base64([]),
  base64([0x00]),
  base64([0x10]),
  base64([0x20, 0x21]),
  base64([0x30, 0x31]),
  base64([0x40, 0x41, 0x42, 0x43]),
  base64(run(0x50, 8)),
  base64(run(0x60, 16)),
];

const rfcRoots = [
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
  '6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d',
  'fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125',
  'aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77',
  'd37ee418976dd95753c1c73862b9398fa2a2cf9b4ff0fdfe8b30cd95209614b7',
  '4e3bbb1f7b478dcfe71fb631631519a3bca12c9aefca1612bfce4c13a86264d4',
  '76e67dadbcdf1e10e1b74ddc608abd2f98dfb16fbce75277b5232a127f2087ef',
  'ddb89be403809e325750d3d263cd78929c2942b7942a34b77e122c9594a74c8c',
  '5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328',
];

async function rfcHashes(size: number): Promise<string[]> {
  return Promise.all(rfcLeaves.slice(0, size).map(leafHash));
}

async function pairwiseRoot(hashes: string[]): Promise<string> {
  let row = hashes;
  while (row.length > 1) {
    const next: string[] = [];
    for (let i = 0; i + 1 < row.length; i += 2) {
      next.push(await node(row[i] as string, row[i + 1] as string));
    }
    if (row.length % 2 === 1) {
      next.push(row[row.length - 1] as string);
    }
    row = next;
  }
  return row[0] as string;
}

async function proofFor(hashes: string[], index: number): Promise<ProofView> {
  const nodes = await levels(hashes);
  const chain = pathNodes(hashes.length, index);
  const path = chain.slice(0, -1).map((at, i) => {
    const parent = nodes[chain[i + 1] as number];
    const sibling = parent?.left === at ? parent.right : parent?.left;
    return nodes[sibling ?? -1]?.hash ?? '';
  });
  return {
    index,
    size: hashes.length,
    encoding: '',
    leafHash: hashes[index] as string,
    path,
    root: nodes[nodes.length - 1]?.hash ?? '',
  };
}

test('canHashWhereWebCryptoExists', () => {
  expect(canHash()).toBe(true);
});

test('hashesALeafWithTheZeroPrefixAndANodeWithTheOnePrefix', async () => {
  expect(await leafHash(rfcLeaves[0] as string)).toBe(rfcRoots[1]);
  const [a, b] = await rfcHashes(2);
  expect(await node(a as string, b as string)).toBe(rfcRoots[2]);
});

test('verifiesTheProofThatTheServersCoreTestPins', async () => {
  const result = await verifyProof(pinned);
  expect(result.matches).toBe(true);
  expect(result.root).toBe(pinned.root);
  expect(result.steps).toHaveLength(3);
  expect(result.steps.map((step) => step.side)).toEqual(['left', 'right', 'left']);
  expect(result.steps.map((step) => step.sibling)).toEqual(pinned.path);
  expect(result.steps[2]?.result).toBe(pinned.root);
});

test('hashesTheServersEncodingToTheLeafHashItPinnedAndTheProofVerifies', async () => {
  expect(await leafHash(pinned.encoding)).toBe(pinned.leafHash);
  const hashed = { ...pinned, leafHash: await leafHash(pinned.encoding) };
  expect((await verifyProof(hashed)).matches).toBe(true);
});

test('failsWhenASiblingIsChanged', async () => {
  const [first, , third] = pinned.path as [string, string, string];
  const changed = { ...pinned, path: [first, 'ff'.repeat(32), third] };
  const result = await verifyProof(changed);
  expect(result.matches).toBe(false);
  expect(result.root).not.toBe(pinned.root);
});

test('failsWhenTheLeafOrTheRootIsChangedOrThePathIsTheWrongLength', async () => {
  expect((await verifyProof({ ...pinned, leafHash: 'aa'.repeat(32) })).matches).toBe(false);
  expect((await verifyProof({ ...pinned, root: 'aa'.repeat(32) })).matches).toBe(false);
  expect((await verifyProof({ ...pinned, path: pinned.path.slice(1) })).matches).toBe(false);
  const longer = { ...pinned, path: [...pinned.path, 'aa'.repeat(32)] };
  expect((await verifyProof(longer)).matches).toBe(false);
  expect((await verifyProof({ ...pinned, index: 7 })).matches).toBe(false);
});

test('drawsTheRfc6962TreeWithTheRootsOfTheTestVectors', async () => {
  for (let size = 1; size <= 8; size += 1) {
    const nodes = await levels(await rfcHashes(size));
    expect(nodes).toHaveLength(2 * size - 1);
    expect(nodes[nodes.length - 1]?.hash, `size ${size}`).toBe(rfcRoots[size]);
  }
});

test('splitsAtTheLargestPowerOfTwoBelowTheSize', () => {
  const nodes = shape(['a', 'b', 'c', 'd', 'e']);
  const root = nodes[nodes.length - 1];
  expect(root?.level).toBe(3);
  expect(nodes[root?.left ?? -1]).toMatchObject({ start: 0, end: 4, level: 2 });
  expect(root?.right).toBe(4);
  expect(shape(['a', 'b', 'c'])[4]).toMatchObject({ start: 0, end: 3, left: 3, right: 2 });
});

test('hasTwoNodesLessOnePerLeafAndTheRightRootForEachSize', { timeout: 60000 }, async () => {
  const hashes = await Promise.all(
    Array.from({ length: 1000 }, (_, i) => leafHash(base64([i % 256, i >> 8]))),
  );
  for (const size of [1, 2, 3, 5, 8, 1000]) {
    const nodes = await levels(hashes.slice(0, size));
    expect(nodes).toHaveLength(2 * size - 1);
    const expected = await pairwiseRoot(hashes.slice(0, size));
    expect(nodes[nodes.length - 1]?.hash, `size ${size}`).toBe(expected);
  }
});

test('givesAnEmptyTreeForNoLeaves', async () => {
  expect(await levels([])).toEqual([]);
});

test('findsTheNodesOnALeafsPathFromTheLeafToTheRoot', () => {
  expect(pathNodes(1, 0)).toEqual([0]);
  expect(pathNodes(7, 5)).toHaveLength(4);
  expect(pathNodes(7, 5)[0]).toBe(5);
  expect(pathNodes(7, 5).at(-1)).toBe(12);
  expect(pathNodes(8, 3)).toHaveLength(4);
  expect(pathNodes(4, 9)).toEqual([]);
});

test('theProofOfEveryLeafOfEveryRfcSizeVerifies', { timeout: 60000 }, async () => {
  for (let size = 1; size <= 8; size += 1) {
    const hashes = await rfcHashes(size);
    for (let index = 0; index < size; index += 1) {
      const proof = await proofFor(hashes, index);
      expect((await verifyProof(proof)).matches, `${index} of ${size}`).toBe(true);
    }
  }
  const hashes = await Promise.all(Array.from({ length: 13 }, (_, i) => leafHash(base64([i]))));
  for (let index = 0; index < 13; index += 1) {
    expect((await verifyProof(await proofFor(hashes, index))).matches).toBe(true);
  }
});
