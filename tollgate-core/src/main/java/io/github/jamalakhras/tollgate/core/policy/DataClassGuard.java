package io.github.jamalakhras.tollgate.core.policy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record DataClassGuard(Map<String, List<String>> regions) implements Guard {

    public static final String TYPE = "data-class";

    public DataClassGuard {
        regions = regions.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision check(GuardRequest request) {
        String dataClass = request.dataClass();
        if (dataClass == null) {
            return Decision.deny(Refusal.POLICY_DENIED, "The request has no data class");
        }
        List<String> allowed = regions.getOrDefault(dataClass, List.of());
        String region = request.region();
        if (region == null || !allowed.contains(region)) {
            String where = region == null ? "has no region" : "is in region '" + region + "'";
            return Decision.deny(
                    Refusal.POLICY_DENIED,
                    "Data class '" + dataClass + "' may go only to regions " + allowed + "; the model '"
                            + request.model() + "' " + where);
        }
        return Decision.ALLOW;
    }
}
