package io.github.jamalakhras.tollgate.server.gateway;

final class ReloadRejected extends RuntimeException {

    static final String INVALID_CONFIG = "invalid_config";
    static final String RESTART_REQUIRED = "restart_required";

    private static final long serialVersionUID = 1L;

    private final String code;

    ReloadRejected(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
