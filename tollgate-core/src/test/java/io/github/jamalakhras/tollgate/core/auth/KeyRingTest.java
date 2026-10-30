package io.github.jamalakhras.tollgate.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.config.TenantKey;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyRingTest {

    private final ApiKey key = ApiKey.generate(new SecureRandom());
    private final KeyRing ring = new KeyRing(List.of(new TenantKey(key.id(), "support-bot", key.sha256())));

    @Test
    void findsTheStoredKeyAndItsTenant() {
        assertThat(ring.authenticate(key.value())).contains(new TenantKey(key.id(), "support-bot", key.sha256()));
    }

    @Test
    void rejectsAKnownIdWithTheWrongSecret() {
        ApiKey forged = new ApiKey(key.id(), "Z".repeat(43));
        assertThat(ring.authenticate(forged.value())).isEmpty();
    }

    @Test
    void rejectsUnknownAndMalformedKeys() {
        assertThat(ring.authenticate(ApiKey.generate(new SecureRandom()).value()))
                .isEmpty();
        assertThat(ring.authenticate("Bearer " + key.value())).isEmpty();
    }
}
