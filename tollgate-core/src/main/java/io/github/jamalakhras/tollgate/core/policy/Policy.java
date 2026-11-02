package io.github.jamalakhras.tollgate.core.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record Policy(List<Guard> guards, Map<Feature, List<String>> features) {

    public static final Policy NONE = new Policy(List.of());

    public Policy {
        guards = List.copyOf(guards);
        Map<Feature, List<String>> copied = new EnumMap<>(Feature.class);
        features.forEach((feature, dataClasses) -> copied.put(feature, List.copyOf(dataClasses)));
        features = Map.copyOf(copied);
    }

    public Policy(List<Guard> guards) {
        this(guards, Map.of());
    }

    public boolean allows(Feature feature, @Nullable String dataClass) {
        return dataClass != null && features.getOrDefault(feature, List.of()).contains(dataClass);
    }

    public Evaluation evaluate(GuardRequest request) {
        List<GuardStep> steps = new ArrayList<>(guards.size());
        for (Guard guard : guards) {
            long start = System.nanoTime();
            Decision decision = guard.check(request);
            steps.add(new GuardStep(guard.type(), decision, Duration.ofNanos(System.nanoTime() - start)));
            if (decision instanceof Decision.Deny) {
                return new Evaluation(decision, steps);
            }
        }
        return new Evaluation(Decision.ALLOW, steps);
    }

    public record Evaluation(Decision decision, List<GuardStep> steps) {

        public Evaluation {
            steps = List.copyOf(steps);
        }
    }
}
