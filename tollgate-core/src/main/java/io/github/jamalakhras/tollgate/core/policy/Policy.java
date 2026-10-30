package io.github.jamalakhras.tollgate.core.policy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record Policy(List<Guard> guards) {

    public static final Policy NONE = new Policy(List.of());

    public Policy {
        guards = List.copyOf(guards);
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
