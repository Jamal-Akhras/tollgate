package io.github.jamalakhras.tollgate.core.audit;

import java.util.ArrayList;
import java.util.List;

final class MerkleTree {

    private static final byte[] LEAF = {0};
    private static final byte[] NODE = {1};

    private MerkleTree() {}

    static byte[] leafHash(byte[] data) {
        return Digests.sha256(LEAF, data);
    }

    static byte[] root(List<byte[]> leafHashes) {
        if (leafHashes.isEmpty()) {
            return Digests.sha256();
        }
        if (leafHashes.size() == 1) {
            return leafHashes.getFirst();
        }
        int split = Integer.highestOneBit(leafHashes.size() - 1);
        return Digests.sha256(
                NODE, root(leafHashes.subList(0, split)), root(leafHashes.subList(split, leafHashes.size())));
    }

    static List<byte[]> path(List<byte[]> leafHashes, int index) {
        if (leafHashes.size() == 1) {
            return List.of();
        }
        int split = Integer.highestOneBit(leafHashes.size() - 1);
        List<byte[]> left = leafHashes.subList(0, split);
        List<byte[]> right = leafHashes.subList(split, leafHashes.size());
        List<byte[]> path = new ArrayList<>();
        if (index < split) {
            path.addAll(path(left, index));
            path.add(root(right));
        } else {
            path.addAll(path(right, index - split));
            path.add(root(left));
        }
        return path;
    }
}
