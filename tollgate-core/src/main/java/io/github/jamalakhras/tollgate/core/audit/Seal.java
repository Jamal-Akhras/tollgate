package io.github.jamalakhras.tollgate.core.audit;

import java.time.Instant;
import java.util.Objects;

public record Seal(
        long batch,
        int records,
        Instant time,
        String previous,
        String root,
        String key,
        String hmacKey,
        String signature)
        implements AuditEntry {

    public Seal {
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be at least 1");
        }
        if (records < 1) {
            throw new IllegalArgumentException("records must be at least 1");
        }
        Objects.requireNonNull(time, "time");
        Digests.requireSha256("previous", previous);
        Digests.requireSha256("root", root);
        Digests.requireKeyId("key", key);
        Digests.requireKeyId("hmacKey", hmacKey);
        Objects.requireNonNull(signature, "signature");
    }

    Seal withSignature(String signature) {
        return new Seal(batch, records, time, previous, root, key, hmacKey, signature);
    }
}
