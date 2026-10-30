package io.github.jamalakhras.tollgate.core.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record Provider(
        WireFormat format,
        URI baseUrl,
        @Nullable SecretRef apiKey,
        @Nullable String region,
        Duration timeout) {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

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
    }

    public URI endpoint(String path) {
        return URI.create(baseUrl + path);
    }
}
