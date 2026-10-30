package io.github.jamalakhras.tollgate.core.audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class MemorySink implements AuditSink {

    private final List<List<AuditEntry>> writes = new ArrayList<>();
    private final Optional<Seal> lastSeal;
    private final CountDownLatch writing = new CountDownLatch(1);
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile @Nullable IOException failure;
    private volatile boolean closed;

    MemorySink() {
        this(Optional.empty());
    }

    MemorySink(Optional<Seal> lastSeal) {
        this.lastSeal = lastSeal;
    }

    @Override
    public Optional<Seal> lastSeal() {
        return lastSeal;
    }

    @Override
    public void write(List<AuditEntry> entries) throws IOException {
        writing.countDown();
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        IOException failed = failure;
        if (failed != null) {
            throw failed;
        }
        synchronized (writes) {
            writes.add(List.copyOf(entries));
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    void hold() {
        gate = new CountDownLatch(1);
    }

    void release() {
        gate.countDown();
    }

    void awaitFirstWrite() throws InterruptedException {
        if (!writing.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("The writer never wrote");
        }
    }

    void failWith(IOException failure) {
        this.failure = failure;
    }

    boolean closed() {
        return closed;
    }

    int writeCount() {
        synchronized (writes) {
            return writes.size();
        }
    }

    List<AuditEntry> entries() {
        synchronized (writes) {
            return writes.stream().flatMap(List::stream).toList();
        }
    }
}
