package io.github.jamalakhras.tollgate.server.gateway;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {ChatCompletionsController.class, ModelsController.class})
class OpenAiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenAiErrorHandler.class);

    @ExceptionHandler
    ResponseEntity<OpenAiError> handle(GatewayException e) {
        return ResponseEntity.status(e.status())
                .body(new OpenAiError(
                        new OpenAiError.Detail(String.valueOf(e.getMessage()), typeOf(e), e.param(), e.code())));
    }

    @ExceptionHandler
    ResponseEntity<OpenAiError> handleUnexpected(Exception e) {
        log.error("Request failed", e);
        return ResponseEntity.internalServerError()
                .body(new OpenAiError(new OpenAiError.Detail(
                        "Tollgate could not complete the request", "server_error", null, "internal_error")));
    }

    private static String typeOf(GatewayException e) {
        if (e.code().equals(GatewayException.BUDGET_EXCEEDED)) {
            return "insufficient_quota";
        }
        return e.status().is5xxServerError() ? "server_error" : "invalid_request_error";
    }

    record OpenAiError(Detail error) {

        record Detail(String message, String type, @Nullable String param, String code) {}
    }
}
