package io.github.jamalakhras.tollgate.server.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MessagesController.class)
class AnthropicErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AnthropicErrorHandler.class);

    @ExceptionHandler
    ResponseEntity<AnthropicError> handle(GatewayException e) {
        return ResponseEntity.status(e.status())
                .body(AnthropicError.of(typeOf(e.status()), String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<AnthropicError> handleUnexpected(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.internalServerError()
                .body(AnthropicError.of("api_error", "Tollgate could not complete the request"));
    }

    private static String typeOf(HttpStatus status) {
        return switch (status) {
            case UNAUTHORIZED -> "authentication_error";
            case FORBIDDEN -> "permission_error";
            case NOT_FOUND -> "not_found_error";
            case CONTENT_TOO_LARGE -> "request_too_large";
            case TOO_MANY_REQUESTS -> "rate_limit_error";
            default -> status.is5xxServerError() ? "api_error" : "invalid_request_error";
        };
    }

    record AnthropicError(String type, Detail error) {

        static AnthropicError of(String type, String message) {
            return new AnthropicError("error", new Detail(type, message));
        }

        record Detail(String type, String message) {}
    }
}
