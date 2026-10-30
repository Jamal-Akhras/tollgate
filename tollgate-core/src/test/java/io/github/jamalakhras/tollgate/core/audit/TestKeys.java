package io.github.jamalakhras.tollgate.core.audit;

import java.security.PublicKey;
import java.security.SecureRandom;

final class TestKeys {

    static final AuditKeys.NewKeys NEW = AuditKeys.generate(new SecureRandom());
    static final AuditKeys KEYS = AuditKeys.of(NEW.publicKey(), NEW.signingKey(), NEW.hmacKey());
    static final PublicKey PUBLIC_KEY = AuditKeys.publicKey(NEW.publicKey());

    private TestKeys() {}
}
