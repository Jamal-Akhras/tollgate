package io.github.jamalakhras.tollgate.core.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Sealer {

    private final AuditKeys keys;
    private final List<byte[]> leaves = new ArrayList<>();
    private long batch;
    private String previous;

    Sealer(AuditKeys keys, Optional<Seal> last) {
        this.keys = keys;
        this.batch = last.map(Seal::batch).orElse(0L);
        this.previous = last.map(Seal::root).orElse(TrailVerifier.GENESIS);
    }

    void add(AuditRecord record) {
        leaves.add(MerkleTree.leafHash(Encoding.of(record)));
    }

    int unsealed() {
        return leaves.size();
    }

    Seal seal(Instant time) {
        String root = Digests.HEX.formatHex(MerkleTree.root(leaves));
        Seal unsigned = new Seal(batch + 1, leaves.size(), time, previous, root, keys.keyId(), keys.hmacKeyId(), "");
        Seal seal = unsigned.withSignature(keys.sign(Encoding.of(unsigned)));
        batch = seal.batch();
        previous = root;
        leaves.clear();
        return seal;
    }
}
