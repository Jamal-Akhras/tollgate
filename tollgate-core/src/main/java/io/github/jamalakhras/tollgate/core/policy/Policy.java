package io.github.jamalakhras.tollgate.core.policy;

import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

public record Policy(
        List<Guard> guards,
        Map<Feature, List<String>> features,
        Map<Detector, List<String>> redact,
        Map<Detector, List<String>> refuse) {

    public static final Policy NONE = new Policy(List.of());

    public Policy {
        guards = List.copyOf(guards);
        features = copy(features);
        redact = copy(redact);
        refuse = copy(refuse);
    }

    public Policy(List<Guard> guards, Map<Feature, List<String>> features) {
        this(guards, features, Map.of(), Map.of());
    }

    public Policy(List<Guard> guards) {
        this(guards, Map.of());
    }

    public boolean allows(Feature feature, @Nullable String dataClass) {
        return dataClass != null && features.getOrDefault(feature, List.of()).contains(dataClass);
    }

    public Set<Detector> redacts(@Nullable String dataClass) {
        return detectors(redact, dataClass);
    }

    public Set<Detector> refuses(@Nullable String dataClass) {
        return detectors(refuse, dataClass);
    }

    private static Set<Detector> detectors(Map<Detector, List<String>> byDetector, @Nullable String dataClass) {
        Set<Detector> detectors = new TreeSet<>();
        byDetector.forEach((detector, dataClasses) -> {
            if (dataClass != null && dataClasses.contains(dataClass)) {
                detectors.add(detector);
            }
        });
        return detectors.isEmpty() ? Set.of() : Collections.unmodifiableSet(detectors);
    }

    private static <K> Map<K, List<String>> copy(Map<K, List<String>> byKey) {
        Map<K, List<String>> copied = new HashMap<>();
        byKey.forEach((key, dataClasses) -> copied.put(key, List.copyOf(dataClasses)));
        return Map.copyOf(copied);
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
