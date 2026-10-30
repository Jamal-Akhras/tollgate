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
    void findsTheTenantOfAValidKey() {
        assertThat(ring.tenantOf(key.value())).contains("support-bot");
    }

    @Test
    void rejectsAKnownIdWithTheWrongSecret() {
        ApiKey forged = new ApiKey(key.id(), "Z".repeat(43));
        assertThat(ring.tenantOf(forged.value())).isEmpty();
    }

    @Test
    void rejectsUnknownAndMalformedKeys() {
        assertThat(ring.tenantOf(ApiKey.generate(new SecureRandom()).value())).isEmpty();
        assertThat(ring.tenantOf("Bearer " + key.value())).isEmpty();
    }
}
