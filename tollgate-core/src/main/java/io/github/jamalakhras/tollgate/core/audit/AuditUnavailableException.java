package io.github.jamalakhras.tollgate.core.audit;

import org.jspecify.annotations.Nullable;

public final class AuditUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AuditUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
