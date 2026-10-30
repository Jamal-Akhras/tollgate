package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    private static final Clock CLOCK = Clock.fixed(Records.TIME, ZoneOffset.UTC);
    private static final AuditLog.Limits LIMITS =
            new AuditLog.Limits(3, Duration.ofHours(1), 100, Duration.ofSeconds(5));

    private final MemorySink sink = new MemorySink();

    private AuditLog start(boolean synchronous, AuditLog.Limits limits) throws IOException {
        return AuditLog.start(sink, TestKeys.KEYS, CLOCK, synchronous, limits);
    }

    @Test
    void writesRecordsInOrderAndSealsEveryFullBatch() throws Exception {
        List<AuditRecord> records = Trails.requests(3);
        try (AuditLog log = start(true, LIMITS)) {
            records.forEach(log::append);
            assertThat(sink.entries()).hasSize(7 + 2);
        }

        assertThat(sink.entries()).isEqualTo(Trails.sealed(records, 3));
        assertThat(Trails.verify(sink.entries()).intact()).isTrue();
        assertThat(sink.closed()).isTrue();
    }

    @Test
    void sealsAnOpenBatchOnceItIsOldEnough() throws Exception {
        try (AuditLog log =
                start(true, new AuditLog.Limits(1_000, Duration.ofMillis(20), 100, Duration.ofSeconds(5)))) {
            log.append(Records.refused("a"));

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (sink.entries().size() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertThat(sink.entries()).hasSize(2).last().isInstanceOf(Seal.class);
        }
    }

    @Test
    void continuesTheChainFromTheLastSealInTheSink() throws Exception {
        List<AuditEntry> earlier = Trails.sealed(Trails.requests(2), 3);
        MemorySink resumed = new MemorySink(Optional.of((Seal) earlier.getLast()));
        try (AuditLog log = AuditLog.start(resumed, TestKeys.KEYS, CLOCK, true, LIMITS)) {
            log.append(Records.refused("later"));
        }

        assertThat(((Seal) resumed.entries().getLast()).batch()).isEqualTo(3);
        assertThat(Trails.verify(earlier, resumed.entries()).intact()).isTrue();
    }

    @Test
    void refusesEveryRecordOnceAWriteHasFailed() throws Exception {
        IOException diskFull = new IOException("No space left on device");
        sink.failWith(diskFull);
        try (AuditLog log = start(true, LIMITS)) {
            assertThatThrownBy(() -> log.append(Records.refused("a")))
                    .isInstanceOf(AuditUnavailableException.class)
                    .hasMessage("The audit record could not be written")
                    .hasCause(diskFull);
            assertThat(log.healthy()).isFalse();
            assertThatThrownBy(() -> log.append(Records.refused("b")))
                    .hasMessage("The audit trail stopped after a write failed")
                    .hasCause(diskFull);
        }
    }

    @Test
    void asynchronousAppendsDoNotWaitForTheDisk() throws Exception {
        sink.hold();
        AuditLog log = start(false, LIMITS);
        log.append(Records.refused("a"));
        log.append(Records.refused("b"));

        assertThat(sink.entries()).isEmpty();

        sink.release();
        log.close();
        assertThat(sink.entries()).hasSize(3);
    }

    @Test
    void refusesRecordsWhenTheQueueIsFull() throws Exception {
        sink.hold();
        try (AuditLog log = start(false, new AuditLog.Limits(3, Duration.ofHours(1), 2, Duration.ofSeconds(5)))) {
            log.append(Records.refused("first"));
            sink.awaitFirstWrite();
            log.append(Records.refused("second"));
            log.append(Records.refused("third"));

            assertThatThrownBy(() -> log.append(Records.refused("fourth")))
                    .isInstanceOf(AuditUnavailableException.class)
                    .hasMessage("The audit queue is full");
            sink.release();
        }
        assertThat(sink.entries()).hasSize(4);
    }

    @Test
    void givesUpWaitingAfterTheSyncTimeout() throws Exception {
        sink.hold();
        try (AuditLog log = start(true, new AuditLog.Limits(3, Duration.ofHours(1), 10, Duration.ofMillis(30)))) {
            assertThatThrownBy(() -> log.append(Records.refused("a")))
                    .isInstanceOf(AuditUnavailableException.class)
                    .hasMessage("The audit record was not written within 30ms");
            sink.release();
        }
    }

    @Test
    void givesUpWaitingWhenTheCallerIsInterrupted() throws Exception {
        sink.hold();
        AtomicReference<Exception> thrown = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        try (AuditLog log = start(true, LIMITS)) {
            Thread caller = Thread.ofVirtual().start(() -> {
                try {
                    log.append(Records.refused("a"));
                } catch (AuditUnavailableException e) {
                    thrown.set(e);
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            sink.awaitFirstWrite();
            caller.interrupt();
            caller.join();
            sink.release();
        }

        assertThat(thrown.get()).hasMessage("Interrupted while waiting for the audit record");
        assertThat(stillInterrupted).isTrue();
    }

    @Test
    void stopsWhenTheWriterIsInterrupted() throws Exception {
        try (AuditLog log = start(true, LIMITS)) {
            log.writer().interrupt();
            log.writer().join(5_000);

            assertThat(log.healthy()).isFalse();
            assertThatThrownBy(() -> log.append(Records.refused("a")))
                    .hasMessage("The audit trail stopped after a write failed")
                    .hasCauseInstanceOf(InterruptedException.class);
        }
    }

    @Test
    void closesTheSinkEvenWhenClosingIsInterrupted() throws Exception {
        sink.hold();
        AuditLog log = start(false, LIMITS);
        log.append(Records.refused("a"));
        sink.awaitFirstWrite();
        AtomicBoolean stillInterrupted = new AtomicBoolean();

        Thread closer = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                log.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        closer.join();
        sink.release();

        assertThat(stillInterrupted).isTrue();
        assertThat(sink.closed()).isTrue();
    }

    @Test
    void refusesRecordsAfterClosingAndClosesOnlyOnce() throws Exception {
        AuditLog log = start(true, LIMITS);
        log.close();
        log.close();

        assertThat(log.healthy()).isFalse();
        assertThatThrownBy(() -> log.append(Records.refused("a"))).hasMessage("The audit trail is closed");
        assertThat(sink.writeCount()).isZero();
    }

    @Test
    void computesHmacsWithItsKey() throws Exception {
        try (AuditLog log = AuditLog.start(sink, TestKeys.KEYS, CLOCK, true)) {
            assertThat(log.hmac(new byte[] {1})).isEqualTo(TestKeys.KEYS.hmac(new byte[] {1}));
        }
    }

    @Test
    void writesRecordsThatQueuedTogetherInOneWrite() throws Exception {
        sink.hold();
        List<Future<?>> appends = new ArrayList<>();
        try (AuditLog log = start(true, new AuditLog.Limits(1_000, Duration.ofHours(1), 100, Duration.ofSeconds(5)));
                ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            appends.add(threads.submit(() -> log.append(Records.refused("first"))));
            sink.awaitFirstWrite();
            for (int i = 0; i < 50; i++) {
                String id = "request-" + i;
                appends.add(threads.submit(() -> log.append(Records.refused(id))));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (log.queued() < 50 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            sink.release();
            for (Future<?> append : appends) {
                append.get();
            }
            assertThat(sink.writeCount()).isEqualTo(2);
        }

        assertThat(sink.entries()).filteredOn(Intent.class::isInstance).hasSize(51);
    }
}
