package io.github.jamalakhras.tollgate.gateway;

import java.nio.charset.StandardCharsets;
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
        return ResponseEntity.status(result.status())
                .headers(headers -> {
                    headers.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                    result.headers().forEach(headers::set);
                })
                .body(result.body().getBytes(StandardCharsets.UTF_8));
    }
}
