package io.github.jamalakhras.tollgate.core.redact;

import java.time.Duration;

record ScanBudget(long started, long nanos) {

    static final ScanBudget UNLIMITED = new ScanBudget(0, Long.MAX_VALUE);

    static ScanBudget of(Duration duration) {
        return new ScanBudget(System.nanoTime(), duration.toNanos());
    }

    boolean spent() {
        return System.nanoTime() - started > nanos;
    }
}
