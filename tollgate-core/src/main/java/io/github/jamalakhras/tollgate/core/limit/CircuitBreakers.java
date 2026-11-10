package io.github.jamalakhras.tollgate.core.limit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

public final class CircuitBreakers {

    public static final int FAILURES = 5;
    public static final Duration OPEN = Duration.ofSeconds(30);

    private static final long SECOND = 1_000_000_000L;

    private final LongSupplier nanoTime;
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    public CircuitBreakers(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public Result admit(String provider) {
        Breaker breaker = breakers.computeIfAbsent(provider, name -> new Breaker());
        long now = nanoTime.getAsLong();
        synchronized (breaker) {
            if (!breaker.open) {
                return new Pass(breaker, false);
            }
            long wait = breaker.openUntil - now;
            if (wait > 0) {
                return new Open(Math.max(1, Math.ceilDiv(wait, SECOND)));
            }
            if (breaker.trying) {
                return new Open(1);
            }
            breaker.trying = true;
            return new Pass(breaker, true);
        }
    }

    public sealed interface Result permits Pass, Open {}

    public record Open(long retryAfterSeconds) implements Result {}

    public enum Change {
        NONE,
        OPENED,
        CLOSED
    }

    public final class Pass implements Result, AutoCloseable {

        private final Breaker breaker;
        private final boolean trial;
        private final AtomicBoolean reported = new AtomicBoolean();

        private Pass(Breaker breaker, boolean trial) {
            this.breaker = breaker;
            this.trial = trial;
        }

        public Change succeeded() {
            if (!reported.compareAndSet(false, true)) {
                return Change.NONE;
            }
            synchronized (breaker) {
                breaker.failures = 0;
                if (!breaker.open) {
                    return Change.NONE;
                }
                breaker.open = false;
                breaker.trying = false;
                return Change.CLOSED;
            }
        }

        public Change failed() {
            if (!reported.compareAndSet(false, true)) {
                return Change.NONE;
            }
            long now = nanoTime.getAsLong();
            synchronized (breaker) {
                if (trial) {
                    breaker.trying = false;
                    breaker.openUntil = now + OPEN.toNanos();
                    return Change.NONE;
                }
                if (breaker.open || ++breaker.failures < FAILURES) {
                    return Change.NONE;
                }
                breaker.failures = 0;
                breaker.open = true;
                breaker.openUntil = now + OPEN.toNanos();
                return Change.OPENED;
            }
        }

        @Override
        public void close() {
            if (reported.compareAndSet(false, true) && trial) {
                synchronized (breaker) {
                    breaker.trying = false;
                }
            }
        }
    }

    private static final class Breaker {

        private int failures;
        private boolean open;
        private boolean trying;
        private long openUntil;
    }
}
