package io.github.jamalakhras.tollgate.core.policy;

import java.util.Objects;

public record MonitoredGuard(Guard guard) implements Guard {

    public MonitoredGuard {
        Objects.requireNonNull(guard, "guard");
    }

    public static Guard underlying(Guard guard) {
        return guard instanceof MonitoredGuard monitored ? monitored.guard() : guard;
    }

    @Override
    public String type() {
        return guard.type();
    }

    @Override
    public Decision check(GuardRequest request) {
        return guard.check(request);
    }
}
