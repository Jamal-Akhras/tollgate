package io.github.jamalakhras.tollgate.core.audit;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record Redactions(Map<String, Integer> values, String sentHmac) {

    public Redactions {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("redactions must count at least one value");
        }
        values.forEach((detector, count) -> {
            if (detector.isBlank()) {
                throw new IllegalArgumentException("redactions must name each detector");
            }
            if (count < 1) {
                throw new IllegalArgumentException("redactions." + detector + " must be at least 1");
            }
        });
        values = Collections.unmodifiableMap(new TreeMap<>(values));
        Digests.requireSha256("sentHmac", sentHmac);
    }
}
