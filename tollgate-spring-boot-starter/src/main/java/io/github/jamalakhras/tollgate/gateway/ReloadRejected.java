package io.github.jamalakhras.tollgate.gateway;

public final class ReloadRejected extends RuntimeException {

    public static final String INVALID_CONFIG = "invalid_config";
    public static final String RESTART_REQUIRED = "restart_required";

    private static final long serialVersionUID = 1L;

    private final String code;

    ReloadRejected(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
