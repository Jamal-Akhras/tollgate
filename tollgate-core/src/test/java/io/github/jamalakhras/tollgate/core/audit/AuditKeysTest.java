package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuditKeysTest {

    private static final class FixedRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, (byte) 7);
        }
    }

    private static final byte[] CONTENT = "batch".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsWhatThePublicKeyVerifies() {
        String signature = TestKeys.KEYS.sign(CONTENT);

        assertThat(AuditKeys.verify(TestKeys.PUBLIC_KEY, CONTENT, signature)).isTrue();
        assertThat(AuditKeys.verify(TestKeys.PUBLIC_KEY, "other".getBytes(StandardCharsets.UTF_8), signature))
                .isFalse();
        assertThat(AuditKeys.verify(TestKeys.PUBLIC_KEY, CONTENT, "not base64!"))
                .isFalse();
        assertThat(AuditKeys.verify(TestKeys.PUBLIC_KEY, CONTENT, "c2hvcnQ=")).isFalse();
    }

    @Test
    void namesEachKeyByTheStartOfItsHash() {
        assertThat(TestKeys.KEYS.keyId()).matches("[0-9a-f]{16}").isEqualTo(AuditKeys.keyId(TestKeys.PUBLIC_KEY));
        assertThat(TestKeys.KEYS.hmacKeyId())
                .isEqualTo(Digests.keyId(Base64.getDecoder().decode(TestKeys.NEW.hmacKey())));
    }

    @Test
    void hmacsDependOnTheKey() {
        AuditKeys.NewKeys other = AuditKeys.generate(new SecureRandom());
        AuditKeys otherKeys = AuditKeys.of(other.publicKey(), other.signingKey(), other.hmacKey());

        assertThat(TestKeys.KEYS.hmac(CONTENT)).matches("[0-9a-f]{64}").isEqualTo(TestKeys.KEYS.hmac(CONTENT));
        assertThat(otherKeys.hmac(CONTENT)).isNotEqualTo(TestKeys.KEYS.hmac(CONTENT));
        assertThat(otherKeys.hmacKeyId()).isNotEqualTo(TestKeys.KEYS.hmacKeyId());
    }

    @Test
    void refusesASigningKeyFromAnotherPair() {
        AuditKeys.NewKeys other = AuditKeys.generate(new SecureRandom());

        assertThatThrownBy(() -> AuditKeys.of(TestKeys.NEW.publicKey(), other.signingKey(), other.hmacKey()))
                .hasMessage("signingKey is not the private half of publicKey");
    }

    @Test
    void refusesAShortHmacKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[AuditKeys.HMAC_KEY_BYTES - 1]);

        assertThatThrownBy(() -> AuditKeys.of(TestKeys.NEW.publicKey(), TestKeys.NEW.signingKey(), shortKey))
                .hasMessage("hmacKey must be at least 32 bytes");
    }

    @Test
    void namesTheSettingThatIsNotAKey() {
        assertThatThrownBy(() -> AuditKeys.of("%%%", TestKeys.NEW.signingKey(), TestKeys.NEW.hmacKey()))
                .hasMessage("publicKey is not valid base64");
        assertThatThrownBy(() ->
                        AuditKeys.of(TestKeys.NEW.signingKey(), TestKeys.NEW.signingKey(), TestKeys.NEW.hmacKey()))
                .hasMessage("publicKey is not an Ed25519 public key");
        assertThatThrownBy(
                        () -> AuditKeys.of(TestKeys.NEW.publicKey(), TestKeys.NEW.publicKey(), TestKeys.NEW.hmacKey()))
                .hasMessage("signingKey is not an Ed25519 private key");
        assertThatThrownBy(() -> AuditKeys.of(TestKeys.NEW.publicKey(), TestKeys.NEW.signingKey(), "%%%"))
                .hasMessage("hmacKey is not valid base64");
    }

    @Test
    void acceptsKeysWithSurroundingWhitespace() {
        AuditKeys keys = AuditKeys.of(
                " " + TestKeys.NEW.publicKey() + "\n", TestKeys.NEW.signingKey() + "\n", TestKeys.NEW.hmacKey());

        assertThat(keys.keyId()).isEqualTo(TestKeys.KEYS.keyId());
    }

    @Test
    void takesEveryKeyFromTheRandomSourceItIsGiven() {
        AuditKeys.NewKeys first = AuditKeys.generate(new FixedRandom());
        AuditKeys.NewKeys second = AuditKeys.generate(new FixedRandom());

        assertThat(second).isEqualTo(first);
        assertThat(Base64.getDecoder().decode(first.hmacKey())).containsOnly(7);
    }

    @Test
    void neverPrintsTheSecretHalves() {
        assertThat(TestKeys.NEW.toString())
                .contains(TestKeys.NEW.publicKey())
                .doesNotContain(TestKeys.NEW.signingKey())
                .doesNotContain(TestKeys.NEW.hmacKey());
    }
}
