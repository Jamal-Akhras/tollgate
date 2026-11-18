package io.github.jamalakhras.tollgate.client;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class Tollgate {

    public static final String DATA_CLASS = "X-Tollgate-Data-Class";
    public static final String USER = "X-Tollgate-User";
    public static final String DRY_RUN = "X-Tollgate-Dry-Run";

    static final String TRACE_ID = "X-Tollgate-Trace-Id";
    static final String COST = "X-Tollgate-Cost";
    static final String COST_MAX = "X-Tollgate-Cost-Max";
    static final String OUTPUT_LIMITED = "X-Tollgate-Output-Limited";
    static final String ERROR = "X-Tollgate-Error";
    static final String RETRY_AFTER = "Retry-After";

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    private Tollgate() {}

    static String baseUrl(@Nullable String given) {
        String url = given == null || given.isEmpty() ? DEFAULT_BASE_URL : given;
        return url.replaceAll("/+$", "");
    }

    static String apiKey(@Nullable String given) {
        if (given == null || given.isEmpty()) {
            throw new IllegalStateException("Set TOLLGATE_API_KEY or pass an API key");
        }
        return given;
    }

    static @Nullable String first(Function<String, List<String>> headers, String name) {
        List<String> values = headers.apply(name);
        return values.isEmpty() ? null : values.get(0);
    }

    static @Nullable Long number(@Nullable String text) {
        return text != null && text.matches("[0-9]{1,18}") ? Long.valueOf(text) : null;
    }
}
