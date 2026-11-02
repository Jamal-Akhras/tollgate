package io.github.jamalakhras.tollgate.core.policy;

public final class RequestFeaturesGuard implements Guard {

    public static final String TYPE = "request-features";
    public static final RequestFeaturesGuard INSTANCE = new RequestFeaturesGuard();

    private RequestFeaturesGuard() {}

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision check(GuardRequest request) {
        RequestFeatures features = request.features();
        if (!features.refused().isEmpty()) {
            return Decision.deny(Refusal.POLICY_DENIED, features.refused().getFirst());
        }
        if (!features.unreviewed().isEmpty()) {
            return Decision.deny(Refusal.POLICY_DENIED, features.unreviewed().getFirst());
        }
        if (!features.used().isEmpty()) {
            Feature feature = features.used().iterator().next();
            String forClass = request.dataClass() == null ? "" : " for data class '" + request.dataClass() + "'";
            return Decision.deny(
                    Refusal.POLICY_DENIED,
                    "This request uses " + feature.description() + ", which this policy does not allow" + forClass);
        }
        return Decision.ALLOW;
    }
}
