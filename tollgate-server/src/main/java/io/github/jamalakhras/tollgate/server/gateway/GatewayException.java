package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

final class GatewayException extends RuntimeException {

    static final String BUDGET_EXCEEDED = Refusal.BUDGET_EXCEEDED.code();

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;
    private final @Nullable String param;

    private GatewayException(HttpStatus status, String code, String message, @Nullable String param) {
        super(message);
        this.status = status;
        this.code = code;
        this.param = param;
    }

    static GatewayException invalidApiKey() {
        return new GatewayException(
                HttpStatus.UNAUTHORIZED, "invalid_api_key", "The Tollgate API key is missing or not valid", null);
    }

    static GatewayException invalidRequest(String message, @Nullable String param) {
        return new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", message, param);
    }

    static GatewayException requestTooLarge(int maxBytes) {
        return new GatewayException(
                HttpStatus.CONTENT_TOO_LARGE,
                "request_too_large",
                "The request body is larger than the limit of " + maxBytes + " bytes",
                null);
    }

    static GatewayException refused(Decision.Deny denial) {
        return switch (denial.refusal()) {
            case INVALID_DATA_CLASS ->
                new GatewayException(HttpStatus.BAD_REQUEST, "invalid_data_class", denial.message(), null);
            case POLICY_DENIED -> new GatewayException(HttpStatus.FORBIDDEN, "policy_denied", denial.message(), null);
            case TOO_LARGE ->
                new GatewayException(HttpStatus.CONTENT_TOO_LARGE, "request_too_large", denial.message(), null);
            case BUDGET_EXCEEDED ->
                new GatewayException(HttpStatus.TOO_MANY_REQUESTS, BUDGET_EXCEEDED, denial.message(), null);
        };
    }

    static GatewayException auditUnavailable() {
        return new GatewayException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "audit_unavailable",
                "Tollgate cannot record this request in its audit trail, so it was not sent",
                null);
    }

    static GatewayException streamingNotSupported() {
        return new GatewayException(
                HttpStatus.BAD_REQUEST,
                "unsupported_parameter",
                "Streaming is not supported yet; send the request with \"stream\": false",
                "stream");
    }

    static GatewayException modelNotFound(String model) {
        return new GatewayException(
                HttpStatus.NOT_FOUND,
                "model_not_found",
                "The model '" + model + "' does not exist or you do not have access to it",
                "model");
    }

    static GatewayException wrongEndpoint(String model, WireFormat format) {
        return new GatewayException(
                HttpStatus.BAD_REQUEST,
                "wrong_endpoint",
                "The model '" + model + "' is served on " + Endpoints.pathFor(format),
                "model");
    }

    static GatewayException providerUnavailable(String message, boolean timedOut) {
        return timedOut
                ? new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "provider_timeout", message, null)
                : new GatewayException(HttpStatus.BAD_GATEWAY, "provider_unavailable", message, null);
    }

    static GatewayException providerInvalidResponse(String message) {
        return new GatewayException(HttpStatus.BAD_GATEWAY, "provider_invalid_response", message, null);
    }

    static GatewayException providerRejectedCredentials() {
        return new GatewayException(
                HttpStatus.BAD_GATEWAY,
                "provider_auth_failed",
                "The provider rejected Tollgate's credentials; an operator needs to check the provider key",
                null);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    @Nullable
    String param() {
        return param;
    }
}
