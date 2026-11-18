package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.ReloadRejected;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AdminAdvice.class)
class AdminAdvice {

    @ExceptionHandler
    ResponseEntity<AdminError> failed(AdminException e) {
        return ResponseEntity.status(e.status()).body(new AdminError(e.code(), String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> busy(OneAtATime.Busy e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body(new AdminError("audit_busy", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> rejected(ReloadRejected e) {
        HttpStatus status = e.code().equals(ReloadRejected.RESTART_REQUIRED)
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status).body(new AdminError(e.code(), String.valueOf(e.getMessage())));
    }
}
