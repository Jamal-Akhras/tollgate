package io.github.jamalakhras.tollgate.core.policy;

import java.util.Map;
import java.util.Optional;

public final class RefusedFields {

    private static final Map<String, String> REASONS = Map.of(
            "mcp_servers", "it makes the provider connect to other servers",
            "container", "it runs code in a provider sandbox",
            "web_search_options", "it sends searches to a search engine",
            "store", "it keeps the conversation at the provider",
            "metadata", "it keeps the conversation at the provider",
            "service_tier", "it changes the price Tollgate budgets for",
            "modalities", "audio is priced differently from text",
            "audio", "audio is priced differently from text");

    private RefusedFields() {}

    public static Optional<String> reason(String field) {
        return Optional.ofNullable(REASONS.get(field));
    }
}
