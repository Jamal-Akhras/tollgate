package io.github.jamalakhras.tollgate.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditBatchesTest {

    private static final long SEAL_WAIT = 1_400;

    @TempDir
    static Path trail;

    @TempDir
    Path other;

    @BeforeAll
    static void writeTrail() throws Exception {
        try (AuditLog log = open(trail)) {
            log.append(Samples.intent("a"));
            Thread.sleep(SEAL_WAIT);
            log.append(Samples.outcome("a"));
        }
        try (AuditLog log = open(trail)) {
            log.append(Samples.refused("r"));
            Thread.sleep(SEAL_WAIT);
            log.append(Samples.intent("7c0e"));
            log.append(Samples.intent("7c0e.2"));
        }
        append(second(trail), AuditJson.write(Samples.intent("u")) + "\n");
    }

    private static AuditLog open(Path directory) throws IOException {
        return AuditLog.start(JsonLinesAuditSink.open(directory), Samples.KEYS, Clock.systemUTC(), true);
    }

    private static Path second(Path directory) throws IOException {
        return AuditDirectory.files(directory).get(1);
    }

    private static void append(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    private static void pad(Path file) throws IOException {
        append(file, ("x".repeat(1023) + "\n").repeat(9_000));
    }

    private static void writeOldAndNew(Path directory) throws Exception {
        try (AuditLog log = open(directory)) {
            log.append(Samples.intent("old"));
        }
        try (AuditLog log = open(directory)) {
            log.append(Samples.intent("new"));
        }
        pad(second(directory));
    }

    private static List<Long> numbers(List<AuditBatches.Batch> batches) {
        return batches.stream().map(batch -> batch.seal().batch()).toList();
    }

    private static void assertSealed(AuditBatches.Batch batch) {
        assertThat(batch.seal().seals(batch.records())).isTrue();
    }

    private static AuditBatches.Located intentOf(AuditBatches.Found found) {
        return Objects.requireNonNull(found.intent());
    }

    private static AuditBatches.Located outcomeOf(AuditBatches.Found found) {
        return Objects.requireNonNull(found.outcome());
    }

    private static AuditBatches.Batch batchOf(AuditBatches.Located located) {
        return Objects.requireNonNull(located.batch());
    }

    @Test
    void listsBatchesNewestFirstAcrossFiles() throws IOException {
        List<AuditBatches.Batch> batches = AuditBatches.recent(trail, 10);

        assertThat(numbers(batches)).containsExactly(4L, 3L, 2L, 1L);
        assertThat(batches.getFirst().file()).isEqualTo("tollgate-audit-00000002.jsonl");
        assertThat(batches.getLast().file()).isEqualTo("tollgate-audit-00000001.jsonl");
        assertThat(batches.getFirst().records()).extracting(AuditRecord::id).containsExactly("7c0e", "7c0e.2");
        batches.forEach(AuditBatchesTest::assertSealed);
    }

    @Test
    void listsAtMostTheLimit() throws IOException {
        assertThat(numbers(AuditBatches.recent(trail, 2))).containsExactly(4L, 3L);
        assertThat(AuditBatches.recent(trail, 0)).isEmpty();
    }

    @Test
    void dropsABatchCutByTheWindowAndKeepsTheLaterOnes() throws IOException {
        int window = (int) Files.size(second(trail)) - 5;

        assertThat(numbers(AuditBatches.recent(trail, 10, window))).containsExactly(4L);
    }

    @Test
    void growsWhatItReadsUntilTheLimitIsMet() throws IOException {
        for (int limit = 1; limit <= 5; limit++) {
            List<Long> whole = numbers(AuditBatches.recent(trail, limit));

            assertThat(numbers(AuditBatches.recent(trail, limit, AuditBatches.WINDOW, 1)))
                    .isEqualTo(whole);
            assertThat(numbers(AuditBatches.recent(trail, limit, AuditBatches.WINDOW, 300)))
                    .isEqualTo(whole);
        }
    }

    @Test
    void findsTheNewestBatchAtTheEndOfALargeFile() throws Exception {
        try (AuditLog log = open(other)) {
            log.append(Samples.intent("old"));
        }
        Path file = AuditDirectory.files(other).getFirst();
        List<String> batch = Files.readAllLines(file);
        Files.writeString(file, ("x".repeat(1023) + "\n").repeat(6_000));
        append(file, String.join("\n", batch) + "\n");

        List<AuditBatches.Batch> found = AuditBatches.recent(other, 1);

        assertThat(numbers(found)).containsExactly(1L);
        assertSealed(found.getFirst());
    }

    @Test
    void listsNothingForAnEmptyDirectory() throws IOException {
        assertThat(AuditBatches.recent(other, 10)).isEmpty();
    }

    @Test
    void findsABatchInTheWindow() throws IOException {
        AuditBatches.Batch batch = AuditBatches.batch(trail, 3).orElseThrow();

        assertThat(batch.records()).extracting(AuditRecord::id).containsExactly("r");
        assertSealed(batch);
    }

    @Test
    void findsABatchOutsideTheWindowAndOneInAnOlderFile() throws Exception {
        writeOldAndNew(other);

        assertThat(AuditBatches.recent(other, 10)).isEmpty();
        AuditBatches.Batch newer = AuditBatches.batch(other, 2).orElseThrow();
        AuditBatches.Batch older = AuditBatches.batch(other, 1).orElseThrow();

        assertThat(newer.records()).extracting(AuditRecord::id).containsExactly("new");
        assertThat(older.records()).extracting(AuditRecord::id).containsExactly("old");
        assertThat(older.file()).isEqualTo("tollgate-audit-00000001.jsonl");
        assertSealed(newer);
        assertSealed(older);
    }

    @Test
    void findsNothingForANumberThatDoesNotExist() throws Exception {
        assertThat(AuditBatches.batch(trail, 9)).isEmpty();
        assertThat(AuditBatches.batch(trail, 0)).isEmpty();

        writeOldAndNew(other);

        assertThat(AuditBatches.batch(other, 9)).isEmpty();
    }

    @Test
    void stopsLookingOnceASealPassesTheNumber() throws IOException {
        List<String> first = Files.readAllLines(AuditDirectory.files(trail).getFirst());
        List<String> second = Files.readAllLines(second(trail));
        Files.write(
                other.resolve("tollgate-audit-00000001.jsonl"),
                List.of(first.getFirst(), first.get(1), second.get(2), second.get(3), second.get(4)));

        assertThat(numbers(AuditBatches.recent(other, 10))).containsExactly(4L, 1L);
        assertThat(AuditBatches.batch(other, 2)).isEmpty();
    }

    @Test
    void findsAnIntentAndItsOutcomeInDifferentBatches() throws IOException {
        AuditBatches.Found found = AuditBatches.find(trail, "a");

        assertThat(batchOf(intentOf(found)).seal().batch()).isEqualTo(1);
        assertThat(intentOf(found).index()).isZero();
        assertThat(batchOf(outcomeOf(found)).seal().batch()).isEqualTo(2);
        assertThat(outcomeOf(found).index()).isZero();
        assertSealed(batchOf(intentOf(found)));
        assertSealed(batchOf(outcomeOf(found)));
    }

    @Test
    void findsARefusedRequestWithNoOutcome() throws IOException {
        AuditBatches.Found found = AuditBatches.find(trail, "r");

        assertThat(batchOf(intentOf(found)).seal().batch()).isEqualTo(3);
        assertThat(found.outcome()).isNull();
    }

    @Test
    void findsAFallbackAttemptWithoutMatchingItsRequest() throws IOException {
        AuditBatches.Found request = AuditBatches.find(trail, "7c0e");
        AuditBatches.Found attempt = AuditBatches.find(trail, "7c0e.2");

        assertThat(intentOf(request).index()).isZero();
        assertThat(request.outcome()).isNull();
        assertThat(intentOf(attempt).index()).isEqualTo(1);
        assertThat(intentOf(attempt).record().id()).isEqualTo("7c0e.2");
        assertThat(attempt.outcome()).isNull();
    }

    @Test
    void findsARecordAfterTheLastSealWithNoBatch() throws IOException {
        AuditBatches.Found found = AuditBatches.find(trail, "u");

        assertThat(intentOf(found).batch()).isNull();
        assertThat(intentOf(found).index()).isEqualTo(-1);
        assertThat(found.outcome()).isNull();
    }

    @Test
    void findsNothingForAnUnknownId() throws IOException {
        AuditBatches.Found found = AuditBatches.find(trail, "nope");

        assertThat(found.intent()).isNull();
        assertThat(found.outcome()).isNull();
    }

    @Test
    void findsARecordOutsideTheWindow() throws Exception {
        writeOldAndNew(other);

        assertThat(batchOf(intentOf(AuditBatches.find(other, "new"))).seal().batch())
                .isEqualTo(2);
        AuditBatches.Batch old = batchOf(intentOf(AuditBatches.find(other, "old")));
        assertThat(old.seal().batch()).isEqualTo(1);
        assertSealed(old);
        assertThat(AuditBatches.find(other, "nope").intent()).isNull();
    }
}
