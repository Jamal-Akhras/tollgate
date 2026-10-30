package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Admission(@Nullable String dataClass, Decision decision, List<GuardStep> steps) {

    public Admission {
        steps = List.copyOf(steps);
    }

    public boolean admitted() {
        return decision instanceof Decision.Allow;
    }
}
