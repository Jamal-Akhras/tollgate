package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.money.Prices;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecordValidationTest {

    private static final String HASH = "0".repeat(64);

    private static Outcome withStatus(int status) {
        return new Outcome(
                "a", Records.TIME, Outcome.Result.RESPONDED, status, null, Prices.usd("0"), Duration.ZERO, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 200, 599})
    void acceptsHttpStatusCodes(int status) {
        assertThat(withStatus(status).status()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600})
    void rejectsNumbersThatAreNotHttpStatusCodes(int status) {
        assertThatThrownBy(() -> withStatus(status)).hasMessage("status must be an HTTP status code, was " + status);
    }

    @Test
    void requiresRedactionsToCountSomethingAndNameWhatWasSent() {
        assertThatThrownBy(() -> new Redactions(Map.of(), HASH)).hasMessage("redactions must count at least one value");
        assertThatThrownBy(() -> new Redactions(Map.of("email", 0), HASH))
                .hasMessage("redactions.email must be at least 1");
        assertThatThrownBy(() -> new Redactions(Map.of("email", 1), "AB"))
                .hasMessage("sentHmac must be 64 lowercase hex characters");
        assertThatThrownBy(() -> new Redactions(Map.of(" ", 1), HASH)).hasMessage("redactions must name each detector");
        assertThat(new Redactions(Map.of("secret", 3, "codenames", 1, "ip", 1), HASH).values())
                .containsExactly(Map.entry("codenames", 1), Map.entry("ip", 1), Map.entry("secret", 3));
    }

    @Test
    void requiresHashesToBeLowercaseSha256Hex() {
        Intent intent = Records.intent("a");
        assertThatThrownBy(() -> new Outcome(
                        "a", Records.TIME, Outcome.Result.RESPONDED, 200, null, Prices.usd("0"), Duration.ZERO, "AB"))
                .hasMessage("responseHmac must be 64 lowercase hex characters");
        assertThatThrownBy(() -> Records.edit(intent, draft -> draft.requestHmac = "A".repeat(64)))
                .hasMessage("requestHmac must be 64 lowercase hex characters");
    }

    @Test
    void requiresSealsToCarryBothHashes() {
        assertThatThrownBy(() -> new Seal(1, 1, Records.TIME, "0", HASH, "b81e44a0c3f2d917", "4c07a2e19b5d3f60", ""))
                .hasMessage("previous must be 64 lowercase hex characters");
        assertThatThrownBy(() -> new Seal(1, 1, Records.TIME, HASH, "0", "b81e44a0c3f2d917", "4c07a2e19b5d3f60", ""))
                .hasMessage("root must be 64 lowercase hex characters");
    }

    @Test
    void rejectsAUserThatIsNotAnHmac() {
        Intent intent = Records.intent("a");
        for (String user : List.of("alice", "a".repeat(63))) {
            assertThatThrownBy(() -> Records.edit(intent, draft -> draft.user = user))
                    .hasMessage("user must be 64 lowercase hex characters");
        }
    }

    @Test
    void requiresTheConfigVersionToBeAHash() {
        Intent intent = Records.intent("a");
        assertThatThrownBy(() -> Records.edit(intent, draft -> draft.configVersion = "v2"))
                .hasMessage("configVersion must be 64 lowercase hex characters");
    }

    @Test
    void requiresSealsToNameBothKeys() {
        assertThatThrownBy(() -> new Seal(1, 1, Records.TIME, HASH, HASH, "short", "4c07a2e19b5d3f60", ""))
                .hasMessage("key must be 16 lowercase hex characters");
        assertThatThrownBy(() -> new Seal(1, 1, Records.TIME, HASH, HASH, "b81e44a0c3f2d917", "short", ""))
                .hasMessage("hmacKey must be 16 lowercase hex characters");
        assertThatThrownBy(() -> new Seal(0, 1, Records.TIME, HASH, HASH, "b81e44a0c3f2d917", "4c07a2e19b5d3f60", ""))
                .hasMessage("batch must be at least 1");
        assertThatThrownBy(() -> new Seal(1, 0, Records.TIME, HASH, HASH, "b81e44a0c3f2d917", "4c07a2e19b5d3f60", ""))
                .hasMessage("records must be at least 1");
    }

    @Test
    void sealsAreSignedByTheirKeyAndNoOther() {
        Seal seal = sealOf(Trails.requests(2));

        assertThat(seal.signedBy(TestKeys.PUBLIC_KEY)).isTrue();
        assertThat(seal.signedBy(AuditKeys.publicKey(
                        AuditKeys.generate(new SecureRandom()).publicKey())))
                .isFalse();
    }

    @Test
    void sealsAreNotSignedByTheirKeyOnceAnyFieldChanges() {
        Seal seal = sealOf(Trails.requests(2));
        String other = "f".repeat(64);
        List<Seal> changed = List.of(
                new Seal(
                        seal.batch() + 1,
                        seal.records(),
                        seal.time(),
                        seal.previous(),
                        seal.root(),
                        seal.key(),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records() + 1,
                        seal.time(),
                        seal.previous(),
                        seal.root(),
                        seal.key(),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time().plusMillis(1),
                        seal.previous(),
                        seal.root(),
                        seal.key(),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time(),
                        other,
                        seal.root(),
                        seal.key(),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time(),
                        seal.previous(),
                        other,
                        seal.key(),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time(),
                        seal.previous(),
                        seal.root(),
                        "0".repeat(16),
                        seal.hmacKey(),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time(),
                        seal.previous(),
                        seal.root(),
                        seal.key(),
                        "0".repeat(16),
                        seal.signature()),
                new Seal(
                        seal.batch(),
                        seal.records(),
                        seal.time(),
                        seal.previous(),
                        seal.root(),
                        seal.key(),
                        seal.hmacKey(),
                        "AAAA"));

        assertThat(changed).noneMatch(candidate -> candidate.signedBy(TestKeys.PUBLIC_KEY));
    }

    @Test
    void sealsSealTheirRecordsAndNoOtherList() {
        List<AuditRecord> records = Trails.requests(2);
        Seal seal = sealOf(records);
        List<AuditRecord> oneMore = new ArrayList<>(records);
        oneMore.add(Records.intent("extra"));
        List<AuditRecord> changed = new ArrayList<>(records);
        changed.set(1, Records.intent("changed"));

        assertThat(seal.seals(records)).isTrue();
        assertThat(seal.seals(records.subList(0, records.size() - 1))).isFalse();
        assertThat(seal.seals(oneMore)).isFalse();
        assertThat(seal.seals(changed)).isFalse();
    }

    private static Seal sealOf(List<AuditRecord> records) {
        return (Seal) Trails.sealed(records, records.size()).getLast();
    }

    @ParameterizedTest
    @EnumSource(Outcome.Result.class)
    void readsResultsBackFromTheirCodes(Outcome.Result result) {
        assertThat(Outcome.Result.fromCode(result.code())).isEqualTo(result);
    }

    @Test
    void rejectsAnUnknownResult() {
        assertThatThrownBy(() -> Outcome.Result.fromCode("lost")).hasMessage("Unknown result: lost");
    }
}
