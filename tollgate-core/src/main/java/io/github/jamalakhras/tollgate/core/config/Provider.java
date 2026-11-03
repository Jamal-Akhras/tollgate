package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.policy.RefusedFields;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record Provider(
        WireFormat format,
        URI baseUrl,
        @Nullable SecretRef apiKey,
        @Nullable String region,
        Duration timeout,
        List<String> extraFields,
        @Nullable String catalog) {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Map<String, String> CATALOG_HOSTS = Map.ofEntries(
            Map.entry("api.openai.com", "openai"),
            Map.entry("api.anthropic.com", "anthropic"),
            Map.entry("dashscope-intl.aliyuncs.com", "alibaba"),
            Map.entry("dashscope.aliyuncs.com", "alibaba-cn"),
            Map.entry("openrouter.ai", "openrouter"),
            Map.entry("api.deepseek.com", "deepseek"),
            Map.entry("api.mistral.ai", "mistral"),
            Map.entry("api.groq.com", "groq"),
            Map.entry("api.together.xyz", "togetherai"),
            Map.entry("api.fireworks.ai", "fireworks-ai"),
            Map.entry("api.x.ai", "xai"),
            Map.entry("generativelanguage.googleapis.com", "google"),
            Map.entry("api.moonshot.ai", "moonshotai"),
            Map.entry("api.z.ai", "zai"),
            Map.entry("ollama.com", "ollama-cloud"));

    public Provider {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(timeout, "timeout");
        String scheme = baseUrl.getScheme();
        boolean isHttp = "https".equals(scheme) || "http".equals(scheme);
        if (!isHttp || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an absolute http or https URL, was " + baseUrl);
        }
        if (baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null || baseUrl.getRawUserInfo() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials, a query or a fragment");
        }
        if (apiKey != null && "http".equals(scheme) && !LOOPBACK_HOSTS.contains(baseUrl.getHost())) {
            throw new IllegalArgumentException("an API key may only be sent over https, except to this machine");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        baseUrl = URI.create(baseUrl.toString().replaceAll("/+$", ""));
        extraFields = List.copyOf(extraFields);
        for (String field : extraFields) {
            RefusedFields.reason(field).ifPresent(reason -> {
                throw new IllegalArgumentException("extraFields must not include '" + field + "': " + reason);
            });
        }
    }

    public Provider(
            WireFormat format, URI baseUrl, @Nullable SecretRef apiKey, @Nullable String region, Duration timeout) {
        this(format, baseUrl, apiKey, region, timeout, List.of(), null);
    }

    public Optional<String> priceCatalog() {
        return Optional.ofNullable(catalog != null ? catalog : CATALOG_HOSTS.get(baseUrl.getHost()));
    }

    public URI endpoint(String path) {
        return URI.create(baseUrl + path);
    }
}
