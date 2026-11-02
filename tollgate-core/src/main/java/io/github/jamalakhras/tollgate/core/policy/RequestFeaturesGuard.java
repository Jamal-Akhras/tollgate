package io.github.jamalakhras.tollgate.core.policy;

public record RequestFeaturesGuard(Policy policy) implements Guard {

    public static final String TYPE = "request-features";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision check(GuardRequest request) {
        RequestFeatures features = request.features();
        String dataClass = request.dataClass();
        if (!features.refused().isEmpty()) {
            return Decision.deny(Refusal.POLICY_DENIED, features.refused().getFirst());
        }
        if (!features.unreviewed().isEmpty() && !policy.allows(Feature.UNREVIEWED_FIELDS, dataClass)) {
            return Decision.deny(Refusal.POLICY_DENIED, features.unreviewed().getFirst());
        }
        for (Feature feature : features.used()) {
            if (!policy.allows(feature, dataClass)) {
                String forClass = dataClass == null ? "" : " for data class '" + dataClass + "'";
                return Decision.deny(
                        Refusal.POLICY_DENIED,
                        "This request uses " + feature.description() + ", which this policy does not allow" + forClass);
            }
        }
        return Decision.ALLOW;
    }
}
