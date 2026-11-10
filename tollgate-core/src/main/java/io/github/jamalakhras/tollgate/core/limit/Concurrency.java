package io.github.jamalakhras.tollgate.core.limit;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

public final class Concurrency {

    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();

    public Optional<Slot> take(String name, @Nullable Long limit) {
        AtomicLong count = calls.computeIfAbsent(name, key -> new AtomicLong());
        long current;
        do {
            current = count.get();
            if (limit != null && current >= limit) {
                return Optional.empty();
            }
        } while (!count.compareAndSet(current, current + 1));
        return Optional.of(new Slot(count));
    }

    public long inFlight(String name) {
        AtomicLong count = calls.get(name);
        return count == null ? 0 : count.get();
    }

    public static final class Slot implements AutoCloseable {

        private final AtomicLong count;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Slot(AtomicLong count) {
            this.count = count;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                count.decrementAndGet();
            }
        }
    }
}
