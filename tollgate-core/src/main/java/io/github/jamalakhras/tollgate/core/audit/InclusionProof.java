package io.github.jamalakhras.tollgate.core.audit;

import java.util.Base64;
import java.util.List;

public record InclusionProof(int index, int size, String encoding, String leafHash, List<String> path, String root) {

    private static final byte[] NODE = {1};

    public InclusionProof {
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("index must be from 0 to " + (size - 1));
        }
        try {
            Base64.getDecoder().decode(encoding);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("encoding must be base64", e);
        }
        Digests.requireSha256("leafHash", leafHash);
        Digests.requireSha256("root", root);
        path = List.copyOf(path);
        for (String step : path) {
            Digests.requireSha256("path", step);
        }
    }

    public static InclusionProof of(List<? extends AuditRecord> batch, int index) {
        if (index < 0 || index >= batch.size()) {
            throw new IllegalArgumentException("index must be from 0 to " + (batch.size() - 1));
        }
        List<byte[]> leafHashes = hashes(batch);
        byte[] encoding = Encoding.of(batch.get(index));
        return new InclusionProof(
                index,
                batch.size(),
                Base64.getEncoder().encodeToString(encoding),
                Digests.HEX.formatHex(MerkleTree.leafHash(encoding)),
                MerkleTree.path(leafHashes, index).stream()
                        .map(Digests.HEX::formatHex)
                        .toList(),
                Digests.HEX.formatHex(MerkleTree.root(leafHashes)));
    }

    public static List<String> leafHashes(List<? extends AuditRecord> batch) {
        return hashes(batch).stream().map(Digests.HEX::formatHex).toList();
    }

    public boolean verifies() {
        byte[] hash = Digests.sha256(new byte[] {0}, Base64.getDecoder().decode(encoding));
        if (!Digests.HEX.formatHex(hash).equals(leafHash)) {
            return false;
        }
        int fn = index;
        int sn = size - 1;
        byte[] r = hash;
        for (String step : path) {
            if (sn == 0) {
                return false;
            }
            byte[] sibling = Digests.HEX.parseHex(step);
            if ((fn & 1) == 1 || fn == sn) {
                r = Digests.sha256(NODE, sibling, r);
                while ((fn & 1) == 0 && fn != 0) {
                    fn >>= 1;
                    sn >>= 1;
                }
            } else {
                r = Digests.sha256(NODE, r, sibling);
            }
            fn >>= 1;
            sn >>= 1;
        }
        return sn == 0 && Digests.HEX.formatHex(r).equals(root);
    }

    static List<byte[]> hashes(List<? extends AuditRecord> batch) {
        return batch.stream()
                .map(record -> MerkleTree.leafHash(Encoding.of(record)))
                .toList();
    }
}
