package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.providers.ProviderResponse;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class Relay {

    private static final String BEARER = "Bearer ";

    private Relay() {}

    static Optional<String> credential(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.of(authorization.substring(BEARER.length()).strip());
        }
        return Optional.ofNullable(headers.getFirst("x-api-key"));
    }

    static ResponseEntity<byte[]> response(Gateway.Result result) {
        ProviderResponse response = result.response();
        String contentType = response.contentType();
        String retryAfter = response.retryAfter();
        return ResponseEntity.status(response.status())
                .headers(headers -> {
                    headers.set(
                            HttpHeaders.CONTENT_TYPE,
                            contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType);
                    if (retryAfter != null) {
                        headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
                    }
                    result.outputLimitedTo()
                            .ifPresent(tokens -> headers.set(Gateway.OUTPUT_LIMITED_HEADER, Long.toString(tokens)));
                })
                .body(response.body());
    }
}
