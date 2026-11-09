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
        if (limit == null) {
            return Optional.of(Slot.NONE);
        }
        AtomicLong count = calls.computeIfAbsent(name, key -> new AtomicLong());
        long current;
        do {
            current = count.get();
            if (current >= limit) {
                return Optional.empty();
            }
        } while (!count.compareAndSet(current, current + 1));
        return Optional.of(new Slot(count));
    }

    public static final class Slot implements AutoCloseable {

        public static final Slot NONE = new Slot(null);

        private final @Nullable AtomicLong count;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Slot(@Nullable AtomicLong count) {
            this.count = count;
        }

        @Override
        public void close() {
            if (count != null && closed.compareAndSet(false, true)) {
                count.decrementAndGet();
            }
        }
    }
}
