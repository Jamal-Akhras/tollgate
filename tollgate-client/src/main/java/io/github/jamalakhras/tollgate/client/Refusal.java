package io.github.jamalakhras.tollgate.client;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public record Refusal(
        String code,
        String message,
        int status,
        @Nullable String traceId,
        @Nullable Long retryAfter) {

    static Optional<Refusal> of(Function<String, List<String>> headers, int status, @Nullable String message) {
        String code = Tollgate.first(headers, Tollgate.ERROR);
        if (code == null) {
            return Optional.empty();
        }
        return Optional.of(new Refusal(
                code,
                message == null ? "" : message,
                status,
                Tollgate.first(headers, Tollgate.TRACE_ID),
                Tollgate.number(Tollgate.first(headers, Tollgate.RETRY_AFTER))));
    }
}
