package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Outcome(
        String id,
        Instant time,
        Result result,
        @Nullable Integer status,
        @Nullable Usage usage,
        Money cost,
        Duration duration,
        @Nullable String responseHmac)
        implements AuditRecord {

    public Outcome {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(duration, "duration");
        if (status != null && (status < 100 || status > 599)) {
            throw new IllegalArgumentException("status must be an HTTP status code, was " + status);
        }
        if (responseHmac != null) {
            Digests.requireSha256("responseHmac", responseHmac);
        }
    }

    public enum Result {
        RESPONDED,
        UNREACHABLE,
        TIMED_OUT,
        INVALID_RESPONSE;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Result fromCode(String code) {
            return Arrays.stream(values())
                    .filter(result -> result.code().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown result: " + code));
        }
    }
}
