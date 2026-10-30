package io.github.jamalakhras.tollgate.core.policy;

import java.util.Objects;

public sealed interface Decision {

    Decision ALLOW = new Allow();

    static Decision deny(Refusal refusal, String message) {
        return new Deny(refusal, message);
    }

    record Allow() implements Decision {}

    record Deny(Refusal refusal, String message) implements Decision {

        public Deny {
            Objects.requireNonNull(refusal, "refusal");
            Objects.requireNonNull(message, "message");
        }
    }
}
