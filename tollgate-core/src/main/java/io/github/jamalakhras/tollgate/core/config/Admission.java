package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record Admission(
        @Nullable String dataClass,
        Decision decision,
        List<GuardStep> steps,
        Set<Detector> refuse,
        Set<Detector> redact) {

    public Admission {
        steps = List.copyOf(steps);
        refuse = Set.copyOf(refuse);
        redact = Set.copyOf(redact);
    }

    public boolean admitted() {
        return decision instanceof Decision.Allow;
    }

    public boolean scans() {
        return !refuse.isEmpty() || !redact.isEmpty();
    }

    public Admission after(GuardStep step) {
        List<GuardStep> taken = new ArrayList<>(steps);
        taken.add(step);
        return new Admission(dataClass, step.decision(), taken, refuse, redact);
    }
}
