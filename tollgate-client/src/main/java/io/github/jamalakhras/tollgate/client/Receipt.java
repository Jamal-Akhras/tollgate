package io.github.jamalakhras.tollgate.client;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public record Receipt(
        @Nullable String traceId,
        @Nullable String cost,
        @Nullable String costMax,
        @Nullable Long outputLimited) {

    static Receipt of(Function<String, List<String>> headers) {
        return new Receipt(
                Tollgate.first(headers, Tollgate.TRACE_ID),
                Tollgate.first(headers, Tollgate.COST),
                Tollgate.first(headers, Tollgate.COST_MAX),
                Tollgate.number(Tollgate.first(headers, Tollgate.OUTPUT_LIMITED)));
    }
}
