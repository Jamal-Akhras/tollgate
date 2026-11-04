package io.github.jamalakhras.tollgate.core.redact;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Placeholders {

    public static final Placeholders NONE = new Placeholders(Map.of());

    static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Z0-9][A-Z0-9_]*_[1-9]\\d*]");

    private final Map<String, String> values;

    Placeholders(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public String restore(String text) {
        return restore(text, false);
    }

    public String restoreEncoded(String text) {
        return restore(text, true);
    }

    public StreamedText streamed(boolean encoded) {
        return new StreamedText(this, encoded);
    }

    String restore(String text, boolean encoded) {
        if (values.isEmpty() || text.indexOf('[') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder restored = new StringBuilder(text.length());
        while (matcher.find()) {
            String value = values.get(matcher.group());
            String replacement = value == null ? matcher.group() : encoded ? json(value) : value;
            matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(restored).toString();
    }

    int heldFrom(String text) {
        int open = text.lastIndexOf('[');
        if (open < 0) {
            return text.length();
        }
        String tail = text.substring(open);
        for (String placeholder : values.keySet()) {
            if (placeholder.length() > tail.length() && placeholder.startsWith(tail)) {
                return open;
            }
        }
        return text.length();
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
