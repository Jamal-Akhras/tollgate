package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveTailTest {

    private static final Instant T = Instant.parse("2026-11-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T, ZoneOffset.UTC);
    private final AtomicReference<Instant> now = new AtomicReference<>(T);
    private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
    private final Clock moving = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Throwable next = failure.getAndSet(null);
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next instanceof Error e) {
                throw e;
            }
            return now.get();
        }
    };

    @TempDir
    Path directory;

    private final LiveTail tail = new LiveTail(() -> Optional.of(directory), moving, 2_000);

    private static Intent intent(String id) {
        return LiveRecords.intent(id, T, "app", List.of(), Decision.ALLOW, null);
    }

    private static Outcome outcome(String id) {
        return LiveRecords.outcome(id, T, Outcome.Result.RESPONDED, 100, new Money(0, Currency.getInstance("USD")));
    }

    private Path file(String day) {
        return directory.resolve("tollgate-audit-" + day + ".jsonl");
    }

    private static void append(Path file, AuditEntry... entries) throws IOException {
        StringBuilder text = new StringBuilder();
        for (AuditEntry entry : entries) {
            text.append(AuditJson.write(entry)).append('\n');
        }
        append(file, text.toString());
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private long sent() {
        return tail.current().flows().stream().mapToLong(Flow::sent).sum();
    }

    @Test
    void doesNotCountRecordsWrittenBeforeTheFirstTick() throws Exception {
        append(file("20261116"), intent("old"), outcome("old"));

        tail.tick();

        assertThat(tail.current().flows()).isEmpty();
        assertThat(tail.current().calls()).isEmpty();
    }

    @Test
    void countsRecordsAppendedAfterTheFirstTick() throws Exception {
        Path file = file("20261116");
        append(file, intent("old"), outcome("old"));
        tail.tick();

        append(file, intent("new"), outcome("new"));
        tail.tick();

        assertThat(sent()).isEqualTo(1);
        assertThat(tail.current().calls()).extracting(LiveViews.CallView::id).containsExactly("new");
    }

    @Test
    void countsAFileThatAppearsAfterTheFirstTick() throws Exception {
        tail.tick();

        append(file("20261116"), intent("c1"), outcome("c1"));
        tail.tick();

        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void movesToANewerFileFromItsStart() throws Exception {
        append(file("20261116"), intent("old"));
        tail.tick();
        append(file("20261117"), intent("c1"), outcome("c1"));

        tail.tick();

        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void marksSkippedAndJumpsWhenMoreIsWaitingThanAPassMayRead() throws Exception {
        Path file = file("20261116");
        append(file, intent("old"));
        tail.tick();
        for (int i = 0; i < 20; i++) {
            append(file, outcome("lost" + i));
        }

        tail.tick();

        assertThat(tail.current().skipped()).isTrue();
        append(file, intent("c1"), outcome("c1"));
        now.set(T.plusSeconds(30));
        tail.tick();
        assertThat(tail.current().skipped()).isTrue();
        assertThat(sent()).isEqualTo(1);
        now.set(T.plusSeconds(61));
        tail.tick();
        assertThat(tail.current().skipped()).isFalse();
    }

    @Test
    void keepsGoingAfterALineThatIsNotARecord() throws Exception {
        Path file = file("20261116");
        append(file, intent("old"));
        tail.tick();

        append(file, "not a record\n");
        append(file, intent("c1"), outcome("c1"));
        tail.tick();

        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void startsOverWhenTheFileIsReplacedBySmallerOne() throws Exception {
        Path file = file("20261116");
        append(file, intent("old"), outcome("old"), intent("old2"), outcome("old2"));
        tail.tick();
        Files.writeString(file, "");

        tail.tick();

        assertThat(tail.current().skipped()).isTrue();
        append(file, intent("c1"), outcome("c1"));
        tail.tick();
        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void keepsTickingWhenASnapshotFails() throws Exception {
        Path file = file("20261116");
        append(file, intent("old"));
        tail.tick();
        append(file, intent("c1"), outcome("c1"));

        failure.set(new IllegalStateException("boom"));
        tail.tick();
        failure.set(new AssertionError("boom"));
        tail.tick();

        tail.tick();
        assertThat(sent()).isEqualTo(1);
    }

    @Test
    void doesNothingWithoutADirectoryOrAFile() {
        new LiveTail(Optional::empty, CLOCK).tick();
        tail.tick();

        assertThat(tail.current().flows()).isEmpty();
    }
}
