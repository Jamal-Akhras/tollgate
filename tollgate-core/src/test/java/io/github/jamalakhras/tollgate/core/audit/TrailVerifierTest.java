package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class TrailVerifierTest {

    private static final List<AuditEntry> TRAIL = Trails.sealed(Trails.requests(7), 4);

    private static List<AuditEntry> trail() {
        return new ArrayList<>(TRAIL);
    }

    private static int lastSealIndex(List<AuditEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof Seal) {
                return i;
            }
        }
        throw new IllegalStateException("No seal");
    }

    @Test
    void acceptsAnUntouchedTrail() {
        TrailVerifier.Report report = Trails.verify(TRAIL);

        assertThat(report.intact()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.records()).isEqualTo(16);
        assertThat(report.batches()).isEqualTo(4);
        assertThat(report.lastSeal()).isEqualTo(TRAIL.getLast());
    }

    @Test
    void detectsAnEditedRecord() {
        List<AuditEntry> trail = trail();
        Intent original = (Intent) trail.getFirst();
        trail.set(
                0,
                new Intent(
                        original.id(),
                        original.time(),
                        "billing",
                        original.key(),
                        original.endpoint(),
                        original.model(),
                        original.provider(),
                        original.upstream(),
                        original.dataClass(),
                        original.configVersion(),
                        original.requestHmac(),
                        original.steps(),
                        original.decision(),
                        original.reservation()));

        assertThat(Trails.messages(Trails.verify(trail).failures()))
                .containsExactly("Batch 1 does not match the records it seals");
    }

    @Test
    void verifiesATrailThatMixesRecordVersions() {
        List<AuditRecord> records = new ArrayList<>(Trails.requests(3));
        records.add(Records.redacted("redacted-1"));
        records.add(Records.outcome("redacted-1"));
        List<AuditEntry> trail = Trails.sealed(records, 3);

        assertThat(Trails.verify(trail).intact()).isTrue();

        int at = trail.indexOf(Records.redacted("redacted-1"));
        Intent redacted = (Intent) trail.get(at);
        trail.set(
                at,
                new Intent(
                        redacted.id(),
                        redacted.time(),
                        redacted.tenant(),
                        redacted.key(),
                        redacted.endpoint(),
                        redacted.model(),
                        redacted.provider(),
                        redacted.upstream(),
                        redacted.dataClass(),
                        redacted.configVersion(),
                        redacted.requestHmac(),
                        redacted.steps(),
                        redacted.decision(),
                        redacted.reservation(),
                        new Redactions(Map.of(Detector.EMAIL, 1), Records.SENT_HMAC)));

        assertThat(Trails.verify(trail).intact()).isFalse();
    }

    @Test
    void detectsADeletedRecord() {
        List<AuditEntry> trail = trail();
        trail.remove(5);

        assertThat(Trails.messages(Trails.verify(trail).failures()))
                .containsExactly("Batch 2 counts 4 records, but 3 precede it");
    }

    @Test
    void detectsReorderedRecords() {
        List<AuditEntry> trail = trail();
        Collections.swap(trail, 0, 1);

        assertThat(Trails.messages(Trails.verify(trail).failures()))
                .contains("Batch 1 does not match the records it seals");
    }

    @Test
    void detectsARemovedBatch() {
        List<AuditEntry> trail = trail();
        trail.subList(5, 10).clear();

        assertThat(Trails.messages(Trails.verify(trail).failures()))
                .containsExactly(
                        "Outcome for request request-3 does not follow an allowed intent",
                        "Batch 3 follows batch 1",
                        "Batch 3 does not link to the batch before it");
    }

    @Test
    void detectsASealSignedWithAnotherKey() {
        AuditKeys.NewKeys other = AuditKeys.generate(new SecureRandom());
        AuditKeys otherKeys = AuditKeys.of(other.publicKey(), other.signingKey(), other.hmacKey());
        List<AuditEntry> forged = Trails.sealed(Trails.requests(7), 4, new Sealer(otherKeys, Optional.empty()));

        assertThat(Trails.messages(Trails.verify(forged).failures()))
                .hasSize(4)
                .allMatch(message -> message.contains("is signed by key " + otherKeys.keyId()));
    }

    @Test
    void detectsAForgedSignature() {
        List<AuditEntry> trail = trail();
        Seal seal = (Seal) trail.get(4);
        Seal next = (Seal) trail.get(9);
        trail.set(4, seal.withSignature(next.signature()));

        assertThat(Trails.messages(Trails.verify(trail).failures()))
                .containsExactly("Batch 1 has an invalid signature");
    }

    @Test
    void warnsAboutRecordsAfterTheLastSealAndContinuesTheChainInTheNextFile() {
        Sealer sealer = new Sealer(TestKeys.KEYS, Optional.empty());
        List<AuditEntry> crashed = Trails.sealed(Trails.requests(2), 10, sealer);
        crashed.add(Records.intent("in-flight"));
        List<AuditEntry> restarted =
                Trails.sealed(List.of(Records.intent("next"), Records.outcome("next")), 10, sealer);

        TrailVerifier.Report report = Trails.verify(crashed, restarted);

        assertThat(report.intact()).isTrue();
        assertThat(report.warnings())
                .extracting(TrailVerifier.Problem::toString)
                .containsExactly(
                        "trail-0.jsonl:7: 1 records from here to the end of the file are not sealed",
                        "trail-0.jsonl:7: Request in-flight was allowed but has no outcome");
    }

    @Test
    void warnsAboutRecordsAfterTheLastSealOfTheLastFile() {
        List<AuditEntry> trail = trail();
        trail.add(Records.refused("late"));

        TrailVerifier.Report report = Trails.verify(trail);

        assertThat(report.intact()).isTrue();
        assertThat(report.warnings())
                .extracting(TrailVerifier.Problem::toString)
                .containsExactly("trail-0.jsonl:21: 1 records from here to the end of the file are not sealed");
    }

    @Test
    void reportsUnreadableAndTornLines() {
        TrailVerifier verifier = new TrailVerifier(TestKeys.PUBLIC_KEY);
        verifier.startFile("trail.jsonl");
        verifier.unreadable(3, "Not JSON");
        verifier.tornLine(4);

        TrailVerifier.Report report = verifier.finish();

        assertThat(report.intact()).isFalse();
        assertThat(report.failures()).containsExactly(new TrailVerifier.Problem("trail.jsonl", 3, "Not JSON"));
        assertThat(Trails.messages(report.warnings()))
                .containsExactly("The last line is incomplete, as a crash during a write leaves it");
    }

    @Test
    void checksThatOutcomesFollowAllowedIntents() {
        List<AuditRecord> records = List.of(
                Records.outcome("stray"),
                Records.intent("twice"),
                Records.intent("twice"),
                Records.refused("refused"),
                Records.outcome("refused"),
                Records.intent("open"));

        TrailVerifier.Report report = Trails.verify(Trails.sealed(records, 10));

        assertThat(Trails.messages(report.failures()))
                .containsExactly(
                        "Outcome for request stray does not follow an allowed intent",
                        "Request twice has more than one intent",
                        "Outcome for request refused does not follow an allowed intent");
        assertThat(Trails.messages(report.warnings()))
                .containsExactly(
                        "Request twice was allowed but has no outcome", "Request open was allowed but has no outcome");
    }

    enum Change {
        DELETE,
        DUPLICATE,
        SWAP_WITH_NEXT,
        REPLACE_WITH_ANOTHER_RECORD
    }

    @Property
    void anySingleChangeBeforeTheLastSealIsCaught(@ForAll Change change, @ForAll @IntRange(min = 0, max = 100) int at) {
        List<AuditEntry> trail = trail();
        int index = at % lastSealIndex(trail);
        switch (change) {
            case DELETE -> trail.remove(index);
            case DUPLICATE -> trail.add(index, trail.get(index));
            case SWAP_WITH_NEXT -> Collections.swap(trail, index, index + 1);
            case REPLACE_WITH_ANOTHER_RECORD -> trail.set(index, Records.unreachable("request-" + index));
        }

        assertThat(Trails.verify(trail).intact()).isFalse();
    }
}
