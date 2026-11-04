package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class EncodingTest {

    private static final String PREVIOUS = "0d4a" + "3".repeat(60);
    private static final String ROOT = "9f2c" + "4".repeat(60);

    private static String sha256Of(AuditEntry entry) {
        return Digests.HEX.formatHex(Digests.sha256(Encoding.of(entry)));
    }

    @Test
    void encodesASealFieldByFieldInTheDocumentedOrder() {
        Instant time = Instant.parse("2026-11-02T14:03:12.100Z");
        long batch = (1L << 32) + 61;
        Seal seal = new Seal(batch, 214, time, PREVIOUS, ROOT, "b81e44a0c3f2d917", "4c07a2e19b5d3f60", "ignored");

        ByteBuffer expected = ByteBuffer.allocate(2 + 8 + 4 + 12 + 32 + 32 + 20 + 20);
        expected.put((byte) 1).put((byte) 3);
        expected.putLong(batch).putInt(214);
        expected.putLong(time.getEpochSecond()).putInt(time.getNano());
        expected.put(Digests.HEX.parseHex(PREVIOUS)).put(Digests.HEX.parseHex(ROOT));
        expected.putInt(16).put("b81e44a0c3f2d917".getBytes(StandardCharsets.UTF_8));
        expected.putInt(16).put("4c07a2e19b5d3f60".getBytes(StandardCharsets.UTF_8));

        assertThat(Encoding.of(seal)).isEqualTo(expected.array());
    }

    @Test
    void leavesTheSignatureOutOfWhatIsSigned() {
        Seal seal = new Seal(1, 1, Records.TIME, PREVIOUS, ROOT, "b81e44a0c3f2d917", "4c07a2e19b5d3f60", "one");

        assertThat(Encoding.of(seal)).isEqualTo(Encoding.of(seal.withSignature("two")));
    }

    @Test
    void keepsTheEncodingOfEachRecordTypeStable() {
        String id = "7c0e9a51-2f4b-4d0e-9a3c-1b2d3e4f5a6b";

        assertThat(List.of(
                        sha256Of(Records.intent(id)),
                        sha256Of(Records.refused(id)),
                        sha256Of(Records.outcome(id)),
                        sha256Of(Records.unreachable(id))))
                .containsExactly(
                        "185ae1fd4ce5536ab132cb5423ee6b48cda9385e4acf77c71ed454451889d8c5",
                        "ed45d23b67c5db8c89fda0bebf0b3c206c0a1df569638dfe152830e3a9f3890d",
                        "53b6445057e84bea7e72b9e280ed6579c05933043bc70bf7c3f9a1caef4fee29",
                        "b562d814cfec7b6e5201e7cff0083ed91a41960338c91d3625d62a8a6fedff92");
    }

    @Test
    void startsEveryRecordWithTheVersionAndItsType() {
        assertThat(Encoding.of(Records.intent("a"))).startsWith(1, 1);
        assertThat(Encoding.of(Records.outcome("a"))).startsWith(1, 2);
        assertThat(Encoding.of(Records.redacted("a"))).startsWith(2, 1);
    }

    @Test
    void encodesRedactionsAsVersion2WithTheCountsInNameOrderAndTheSentHmac() {
        byte[] plain = Encoding.of(Records.intent("a"));
        byte[] email = "email".getBytes(StandardCharsets.UTF_8);
        byte[] card = "card".getBytes(StandardCharsets.UTF_8);
        ByteBuffer expected = ByteBuffer.allocate(plain.length + 4 + 4 + email.length + 4 + 4 + card.length + 4 + 32);
        expected.put((byte) 2).put(plain, 1, plain.length - 1);
        expected.putInt(2);
        expected.putInt(card.length).put(card).putInt(1);
        expected.putInt(email.length).put(email).putInt(2);
        expected.put(Digests.HEX.parseHex(Records.SENT_HMAC));

        assertThat(Encoding.of(Records.redacted("a"))).isEqualTo(expected.array());
        assertThat(sha256Of(Records.redacted("7c0e9a51-2f4b-4d0e-9a3c-1b2d3e4f5a6b")))
                .isEqualTo("aebc4ee99c5454c53411076c6ed77ce47c1a81daa08b73970ce25b5655c6da2c");
    }

    @Test
    void tellsAMissingDataClassFromAnEmptyOne() {
        Intent missing = Records.refused("a");
        Intent empty = new Intent(
                missing.id(),
                missing.time(),
                missing.tenant(),
                missing.key(),
                missing.endpoint(),
                missing.model(),
                missing.provider(),
                missing.upstream(),
                "",
                missing.configVersion(),
                missing.requestHmac(),
                missing.steps(),
                missing.decision(),
                missing.reservation());

        assertThat(Encoding.of(missing)).isNotEqualTo(Encoding.of(empty));
    }

    @Property
    void neverEncodesTwoDifferentTenantAndKeyPairsAlike(
            @ForAll String tenant, @ForAll String key, @ForAll String otherTenant, @ForAll String otherKey) {
        Assume.that(!(tenant.equals(otherTenant) && key.equals(otherKey)));

        assertThat(Encoding.of(withTenantAndKey(tenant, key)))
                .isNotEqualTo(Encoding.of(withTenantAndKey(otherTenant, otherKey)));
    }

    @Test
    void movingCharactersBetweenFieldsChangesTheEncoding() {
        assertThat(Encoding.of(withTenantAndKey("ab", "c"))).isNotEqualTo(Encoding.of(withTenantAndKey("a", "bc")));
    }

    private static Intent withTenantAndKey(String tenant, String key) {
        Intent intent = Records.intent("a");
        return new Intent(
                intent.id(),
                intent.time(),
                tenant,
                key,
                intent.endpoint(),
                intent.model(),
                intent.provider(),
                intent.upstream(),
                intent.dataClass(),
                intent.configVersion(),
                intent.requestHmac(),
                intent.steps(),
                intent.decision(),
                intent.reservation());
    }
}
