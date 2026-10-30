package io.github.jamalakhras.tollgate.core.config;

import java.util.regex.Pattern;

public final class Names {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private Names() {}

    public static void check(String what, String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(what + " '" + name
                    + "' must start with a letter or digit and use only letters, digits, '.', '_', ':' and '-'");
        }
    }
}
