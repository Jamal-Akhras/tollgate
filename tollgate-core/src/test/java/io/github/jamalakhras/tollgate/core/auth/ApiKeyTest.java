package io.github.jamalakhras.tollgate.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyTest {

    private static final String KEY = "tg_k7Qm2xPa9bCd_" + "Z".repeat(43);

    @Test
    void generatesKeysThatParseBack() {
        ApiKey key = ApiKey.generate(new SecureRandom());
        assertThat(key.value()).matches("tg_[0-9A-Za-z]{12}_[0-9A-Za-z]{43}");
        assertThat(ApiKey.parse(key.value())).contains(key);
    }

    @Test
    void generatesDifferentKeysEachTime() {
        SecureRandom random = new SecureRandom();
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            values.add(ApiKey.generate(random).value());
        }
        assertThat(values).hasSize(1_000);
    }

    @Test
    void hashesTheWholeKeyWithSha256() {
        ApiKey key = ApiKey.parse(KEY).orElseThrow();
        assertThat(key.id()).isEqualTo("k7Qm2xPa9bCd");
        assertThat(key.sha256()).isEqualTo("52f74ec29f4fe6124d408e02e9da82831849fae04d2a4fbd34161b07e563e11b");
    }

    @Test
    void neverPrintsTheSecret() {
        assertThat(ApiKey.parse(KEY).orElseThrow()).hasToString("tg_k7Qm2xPa9bCd_…");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "sk-proj-123",
                "tg_k7Qm2xPa9bCd",
                "tg_k7Qm2xPa9bC_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ",
                "tg_k7Qm2xPa9bCd_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ-",
                " tg_k7Qm2xPa9bCd_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"
            })
    void rejectsAnythingElse(String value) {
        assertThat(ApiKey.parse(value)).isEmpty();
    }

    @Test
    void cannotBeBuiltFromInvalidParts() {
        assertThatThrownBy(() -> new ApiKey("short", "Z".repeat(43))).isInstanceOf(IllegalArgumentException.class);
    }
}
