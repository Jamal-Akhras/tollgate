package io.github.jamalakhras.tollgate.core.policy;

import java.util.Map;
import java.util.Optional;

public final class RefusedFields {

    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("mcp_servers", "it makes the provider connect to other servers"),
            Map.entry("container", "it runs code in a provider sandbox"),
            Map.entry("web_search_options", "it sends searches to a search engine"),
            Map.entry("store", "it keeps the conversation at the provider"),
            Map.entry("metadata", "it keeps the conversation at the provider"),
            Map.entry("service_tier", "it changes the price Tollgate budgets for"),
            Map.entry("speed", "it changes the price Tollgate budgets for"),
            Map.entry("modalities", "audio is priced differently from text"),
            Map.entry("audio", "audio is priced differently from text"));

    private RefusedFields() {}

    public static Optional<String> reason(String field) {
        return Optional.ofNullable(REASONS.get(field));
    }
}
