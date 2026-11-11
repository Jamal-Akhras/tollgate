package io.github.jamalakhras.tollgate.core.policy;

import java.time.Duration;

public record GuardStep(String guard, Decision decision, Duration duration, boolean monitored) {

    public GuardStep(String guard, Decision decision, Duration duration) {
        this(guard, decision, duration, false);
    }
}
