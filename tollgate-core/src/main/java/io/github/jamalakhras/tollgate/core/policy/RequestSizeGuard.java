package io.github.jamalakhras.tollgate.core.policy;

public record RequestSizeGuard(long maxBytes) implements Guard {

    public static final String TYPE = "request-size";

    public RequestSizeGuard {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Decision check(GuardRequest request) {
        if (request.sizeBytes() > maxBytes) {
            return Decision.deny(
                    Refusal.TOO_LARGE,
                    "The request is " + request.sizeBytes() + " bytes; this policy allows " + maxBytes);
        }
        return Decision.ALLOW;
    }
}
