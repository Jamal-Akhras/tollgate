package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record Redactions(Map<Detector, Integer> values, String sentHmac) {

    public Redactions {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("redactions must count at least one value");
        }
        values.forEach((detector, count) -> {
            if (count < 1) {
                throw new IllegalArgumentException("redactions." + detector.configName() + " must be at least 1");
            }
        });
        values = Collections.unmodifiableMap(new EnumMap<>(values));
        Digests.requireSha256("sentHmac", sentHmac);
    }
}
