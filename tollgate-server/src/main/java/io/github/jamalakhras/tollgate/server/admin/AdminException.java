package io.github.jamalakhras.tollgate.server.admin;

import org.springframework.http.HttpStatus;

final class AdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String code;

    private AdminException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static AdminException badQuery(String message) {
        return new AdminException(HttpStatus.BAD_REQUEST, "invalid_request", message);
    }

    static AdminException auditOff(String message) {
        return new AdminException(HttpStatus.CONFLICT, "audit_off", message);
    }

    static AdminException notFound(String message) {
        return new AdminException(HttpStatus.NOT_FOUND, "not_found", message);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
