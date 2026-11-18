package io.github.jamalakhras.tollgate.gateway;

import org.springframework.http.HttpHeaders;

final class ErrorHeaders {

    private ErrorHeaders() {}

    static void add(HttpHeaders headers, GatewayException e) {
        headers.set(Gateway.ERROR_HEADER, e.code());
        if (e.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, e.retryAfter());
        }
        if (e.code().equals(GatewayException.BUDGET_EXCEEDED)) {
            headers.set("x-should-retry", "false");
        }
    }
}
