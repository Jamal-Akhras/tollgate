package io.github.jamalakhras.tollgate.core.audit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

public final class AuditLog implements AutoCloseable {

    private static final Pending STOP = new Pending(null);

    private final AuditSink sink;
    private final AuditKeys keys;
    private final Sealer sealer;
    private final Clock clock;
    private final boolean synchronous;
    private final Limits limits;
    private final BlockingQueue<Pending> queue;
    private final Thread writer;
    private volatile @Nullable Exception failure;
    private volatile boolean closed;
    private long batchOpened;

    private AuditLog(AuditSink sink, AuditKeys keys, Sealer sealer, Clock clock, boolean synchronous, Limits limits) {
        this.sink = sink;
        this.keys = keys;
        this.sealer = sealer;
        this.clock = clock;
        this.synchronous = synchronous;
        this.limits = limits;
        this.queue = new LinkedBlockingQueue<>(limits.capacity());
        this.writer = Thread.ofPlatform().name("tollgate-audit").daemon().unstarted(this::write);
    }

    public static AuditLog start(AuditSink sink, AuditKeys keys, Clock clock, boolean synchronous) throws IOException {
        return start(sink, keys, clock, synchronous, Limits.DEFAULT);
    }

    static AuditLog start(AuditSink sink, AuditKeys keys, Clock clock, boolean synchronous, Limits limits)
            throws IOException {
        AuditLog log = new AuditLog(sink, keys, new Sealer(keys, sink.lastSeal()), clock, synchronous, limits);
        log.writer.start();
        return log;
    }

    public String hmac(byte[] content) {
        return keys.hmac(content);
    }

    public Fingerprint fingerprint() {
        return keys.fingerprint();
    }

    public void append(AuditRecord record) {
        Exception failed = failure;
        if (failed != null) {
            throw new AuditUnavailableException("The audit trail stopped after a write failed", failed);
        }
        if (closed) {
            throw new AuditUnavailableException("The audit trail is closed", null);
        }
        Pending pending = new Pending(record);
        if (!queue.offer(pending)) {
            throw new AuditUnavailableException("The audit queue is full", null);
        }
        if (synchronous) {
            awaitWrite(pending);
        }
    }

    public boolean healthy() {
        return failure == null && !closed;
    }

    int queued() {
        return queue.size();
    }

    Thread writer() {
        return writer;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            boolean stopQueued = false;
            while (!stopQueued && writer.isAlive()) {
                stopQueued = queue.offer(STOP, 100, TimeUnit.MILLISECONDS);
            }
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sink.close();
        }
    }

    private void awaitWrite(Pending pending) {
        try {
            pending.written().get(limits.syncTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            throw new AuditUnavailableException("The audit record could not be written", e.getCause());
        } catch (TimeoutException e) {
            throw new AuditUnavailableException(
                    "The audit record was not written within "
                            + limits.syncTimeout().toMillis() + "ms",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditUnavailableException("Interrupted while waiting for the audit record", e);
        }
    }

    private void write() {
        List<Pending> taken = new ArrayList<>();
        try {
            boolean stopping = false;
            while (!stopping) {
                taken.clear();
                Pending first =
                        sealer.unsealed() == 0 ? queue.take() : queue.poll(nanosUntilSeal(), TimeUnit.NANOSECONDS);
                if (first != null) {
                    taken.add(first);
                    queue.drainTo(taken);
                }
                List<AuditEntry> entries = new ArrayList<>(taken.size() + 1);
                for (Pending pending : taken) {
                    AuditRecord record = pending.record();
                    if (record == null) {
                        stopping = true;
                    } else {
                        entries.add(record);
                        addToBatch(record, entries);
                    }
                }
                if (sealer.unsealed() > 0 && (stopping || nanosUntilSeal() <= 0)) {
                    entries.add(sealer.seal(clock.instant()));
                }
                if (!entries.isEmpty()) {
                    sink.write(entries);
                }
                taken.forEach(pending -> pending.written().complete(null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(taken, e);
        } catch (IOException | RuntimeException e) {
            fail(taken, e);
        }
    }

    private void addToBatch(AuditRecord record, List<AuditEntry> entries) {
        sealer.add(record);
        if (sealer.unsealed() == 1) {
            batchOpened = System.nanoTime();
        }
        if (sealer.unsealed() == limits.batchRecords()) {
            entries.add(sealer.seal(clock.instant()));
        }
    }

    private long nanosUntilSeal() {
        return limits.batchAge().toNanos() - (System.nanoTime() - batchOpened);
    }

    private void fail(List<Pending> taken, Exception cause) {
        failure = cause;
        List<Pending> waiting = new ArrayList<>(taken);
        queue.drainTo(waiting);
        waiting.forEach(pending -> pending.written().completeExceptionally(cause));
    }

    record Limits(int batchRecords, Duration batchAge, int capacity, Duration syncTimeout) {

        static final Limits DEFAULT = new Limits(1_000, Duration.ofSeconds(1), 10_000, Duration.ofSeconds(5));
    }

    private record Pending(@Nullable AuditRecord record, CompletableFuture<@Nullable Void> written) {

        Pending(@Nullable AuditRecord record) {
            this(record, new CompletableFuture<>());
        }
    }
}
