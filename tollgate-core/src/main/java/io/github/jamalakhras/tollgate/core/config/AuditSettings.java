package io.github.jamalakhras.tollgate.core.config;

import java.util.Objects;

public sealed interface AuditSettings {

    AuditSettings DISABLED = new Disabled();

    record Disabled() implements AuditSettings {}

    record Enabled(boolean synchronous, String directory, String publicKey, SecretRef signingKey, SecretRef hmacKey)
            implements AuditSettings {

        public Enabled {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(publicKey, "publicKey");
            Objects.requireNonNull(signingKey, "signingKey");
            Objects.requireNonNull(hmacKey, "hmacKey");
        }
    }
}
