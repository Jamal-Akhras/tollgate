package io.github.jamalakhras.tollgate.gateway.audit;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

public final class AuditBatches {

    static final int WINDOW = 8 << 20;
    private static final int STEP = 4 << 20;
    private static final int FIRST_SPAN = 256 << 10;

    private AuditBatches() {}

    public record Batch(String file, Seal seal, List<AuditRecord> records) {}

    public record Located(AuditRecord record, @Nullable Batch batch, int index) {}

    public record Found(@Nullable Located intent, @Nullable Located outcome) {}

    public static List<Batch> recent(Path directory, int limit) throws IOException {
        return recent(directory, limit, WINDOW);
    }

    static List<Batch> recent(Path directory, int limit, int window) throws IOException {
        return recent(directory, limit, window, FIRST_SPAN);
    }

    static List<Batch> recent(Path directory, int limit, int window, int firstSpan) throws IOException {
        List<Batch> found = new ArrayList<>();
        long left = window;
        for (Path file : AuditDirectory.files(directory).reversed()) {
            if (found.size() >= limit || left <= 0) {
                break;
            }
            long size = Files.size(file);
            long allowed = Math.min(size, left);
            left -= allowed;
            long span = Math.min(allowed, firstSpan);
            List<Batch> read;
            while (true) {
                Pass pass = new Pass(file, batch -> true);
                pass.read(size - span, () -> false);
                read = pass.batches.reversed();
                if (found.size() + read.size() >= limit || span == allowed) {
                    break;
                }
                span = Math.min(allowed, span * 2);
            }
            found.addAll(read);
        }
        return List.copyOf(found.subList(0, Math.min(limit, found.size())));
    }

    public static Optional<Batch> batch(Path directory, long number) throws IOException {
        for (Batch batch : recent(directory, Integer.MAX_VALUE, WINDOW)) {
            if (batch.seal().batch() == number) {
                return Optional.of(batch);
            }
        }
        for (Path file : AuditDirectory.files(directory).reversed()) {
            Pass pass = new Pass(file, batch -> batch.seal().batch() == number);
            pass.read(0, () -> pass.lastBatch >= number);
            if (!pass.batches.isEmpty()) {
                return Optional.of(pass.batches.getFirst());
            }
            if (pass.lastBatch > number && pass.firstBatch <= number) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static Found find(Path directory, String id) throws IOException {
        List<Path> files = AuditDirectory.files(directory).reversed();
        Hits hits = new Hits();
        long left = WINDOW;
        for (Path file : files) {
            if (left <= 0) {
                break;
            }
            long size = Files.size(file);
            long from = Math.max(0, size - left);
            left -= size - from;
            hits.search(file, from, id);
        }
        if (hits.intent == null) {
            for (Path file : files) {
                hits.search(file, 0, id);
                if (hits.intent != null) {
                    break;
                }
            }
        }
        return new Found(hits.intent, hits.outcome);
    }

    private static final class Pass {

        private final Path file;
        private final Predicate<Batch> keep;
        private final List<AuditRecord> pending = new ArrayList<>();
        private final List<Batch> batches = new ArrayList<>();
        private long firstBatch;
        private long lastBatch;

        Pass(Path file, Predicate<Batch> keep) {
            this.file = file;
            this.keep = keep;
        }

        void read(long from, BooleanSupplier done) throws IOException {
            long size = Files.size(file);
            long offset = from;
            while (offset < size && !done.getAsBoolean()) {
                long next = AuditDirectory.follow(file, offset, STEP, this::add);
                if (next == offset) {
                    return;
                }
                offset = next;
            }
        }

        private void add(AuditEntry entry) {
            switch (entry) {
                case AuditRecord record -> pending.add(record);
                case Seal seal -> {
                    if (firstBatch == 0) {
                        firstBatch = seal.batch();
                    }
                    lastBatch = seal.batch();
                    if (seal.records() == pending.size()) {
                        Batch batch = new Batch(file.getFileName().toString(), seal, List.copyOf(pending));
                        if (keep.test(batch)) {
                            batches.add(batch);
                        }
                    }
                    pending.clear();
                }
            }
        }
    }

    private static final class Hits {

        private @Nullable Located intent;
        private @Nullable Located outcome;

        void search(Path file, long from, String id) throws IOException {
            Pass pass = new Pass(
                    file,
                    batch -> batch.records().stream()
                            .anyMatch(record -> record.id().equals(id)));
            pass.read(from, () -> false);
            for (Batch batch : pass.batches) {
                for (int index = 0; index < batch.records().size(); index++) {
                    AuditRecord record = batch.records().get(index);
                    if (record.id().equals(id)) {
                        keep(new Located(record, batch, index));
                    }
                }
            }
            for (AuditRecord record : pass.pending) {
                if (record.id().equals(id)) {
                    keep(new Located(record, null, -1));
                }
            }
        }

        private void keep(Located located) {
            if (located.record() instanceof Intent) {
                intent = located;
            } else {
                outcome = located;
            }
        }
    }
}
