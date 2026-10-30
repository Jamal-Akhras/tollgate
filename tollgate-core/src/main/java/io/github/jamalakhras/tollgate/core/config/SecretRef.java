package io.github.jamalakhras.tollgate.core.config;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record SecretRef(String variable) {

    private static final Pattern ENVIRONMENT_REFERENCE = Pattern.compile("\\$\\{env:([A-Za-z_][A-Za-z0-9_]*)}");

    public SecretRef {
        if (!variable.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Not an environment variable name: " + variable);
        }
    }

    public static SecretRef parse(String reference) {
        Matcher matcher = ENVIRONMENT_REFERENCE.matcher(reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "secrets must be references such as ${env:PROVIDER_API_KEY}, never values");
        }
        return new SecretRef(matcher.group(1));
    }

    public String resolve(Function<String, @Nullable String> environment) {
        String value = environment.apply(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Environment variable " + variable + " is not set");
        }
        return value;
    }

    @Override
    public String toString() {
        return "${env:" + variable + "}";
    }
}
