package io.github.jamalakhras.tollgate.gateway;

public final class EditRejected extends RuntimeException {

    public static final String CONFIG_CHANGED = "config_changed";
    public static final String WRITE_FAILED = "write_failed";

    private static final long serialVersionUID = 1L;

    private final String code;

    EditRejected(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
