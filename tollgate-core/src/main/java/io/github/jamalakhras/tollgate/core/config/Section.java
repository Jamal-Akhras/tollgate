package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class Section {

    private static final Pattern DURATION = Pattern.compile("(\\d{1,9})(ms|s|m)");

    private final String path;
    private final Map<String, ?> values;

    private Section(String path, Map<String, ?> values) {
        this.path = path;
        this.values = values;
    }

    static Section root(Map<String, ?> values) {
        return new Section("", values);
    }

    void requireOnly(String... allowedKeys) {
        Set<String> allowed = Set.of(allowedKeys);
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new InvalidConfigException(pathOf(key) + " is not a recognised setting");
            }
        }
    }

    InvalidConfigException invalid(String key, String problem) {
        return new InvalidConfigException(pathOf(key) + " " + problem);
    }

    boolean has(String key) {
        return values.get(key) != null;
    }

    boolean isText(String key) {
        return values.get(key) instanceof String;
    }

    String path(String key) {
        return pathOf(key);
    }

    String text(String key) {
        String text = optionalText(key);
        if (text == null) {
            throw new InvalidConfigException(pathOf(key) + " is required");
        }
        return text;
    }

    @Nullable
    String optionalText(String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InvalidConfigException(pathOf(key) + " must be a non-empty string");
        }
        return text;
    }

    URI uri(String key) {
        String text = text(key);
        try {
            return URI.create(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException(pathOf(key) + " is not a valid URL: " + text, e);
        }
    }

    Duration duration(String key, Duration fallback) {
        String text = optionalText(key);
        if (text == null) {
            return fallback;
        }
        Matcher matcher = DURATION.matcher(text);
        if (!matcher.matches()) {
            throw new InvalidConfigException(pathOf(key) + " must be a duration such as 500ms, 30s or 2m, was " + text);
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            default -> Duration.ofMinutes(amount);
        };
    }

    boolean flag(String key, boolean fallback) {
        return switch (values.get(key)) {
            case null -> fallback;
            case Boolean flag -> flag;
            default -> throw new InvalidConfigException(pathOf(key) + " must be true or false, was " + values.get(key));
        };
    }

    long optionalWholeNumber(String key, long fallback) {
        return values.get(key) == null ? fallback : wholeNumber(key);
    }

    long wholeNumber(String key) {
        Object value = values.get(key);
        if (value == null) {
            throw new InvalidConfigException(pathOf(key) + " is required");
        }
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new InvalidConfigException(pathOf(key) + " must be a whole number, was " + value);
        }
        return ((Number) value).longValue();
    }

    List<String> texts(String key) {
        return textsAt(pathOf(key), values.get(key));
    }

    Map<String, List<String>> textLists(String key) {
        if (values.get(key) == null) {
            throw new InvalidConfigException(pathOf(key) + " is required");
        }
        return optionalTextLists(key);
    }

    Map<String, List<String>> optionalTextLists(String key) {
        Object value = values.get(key);
        if (value == null) {
            return Map.of();
        }
        Map<String, List<String>> lists = new LinkedHashMap<>();
        asMap(pathOf(key), value).forEach((name, items) -> lists.put(name, textsAt(pathOf(key) + "." + name, items)));
        return lists;
    }

    <T> Map<String, T> sections(String key, Function<Section, T> reader) {
        Object value = values.get(key);
        if (value == null) {
            return Map.of();
        }
        Map<String, T> sections = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : asMap(pathOf(key), value).entrySet()) {
            String childPath = pathOf(key) + "." + entry.getKey();
            sections.put(entry.getKey(), reader.apply(new Section(childPath, asMap(childPath, entry.getValue()))));
        }
        return sections;
    }

    <T> T child(String key, Function<Section, T> reader) {
        T child = optionalChild(key, reader);
        if (child == null) {
            throw new InvalidConfigException(pathOf(key) + " is required");
        }
        return child;
    }

    <T> @Nullable T optionalChild(String key, Function<Section, T> reader) {
        Object value = values.get(key);
        return value == null ? null : reader.apply(new Section(pathOf(key), asMap(pathOf(key), value)));
    }

    @Nullable
    Money optionalMoney(String key, Currency currency) {
        return values.get(key) == null ? null : money(key, currency);
    }

    Money money(String key, Currency currency) {
        Object value = values.get(key);
        BigDecimal amount = switch (value) {
            case null -> throw new InvalidConfigException(pathOf(key) + " is required");
            case Integer number -> BigDecimal.valueOf(number);
            case Long number -> BigDecimal.valueOf(number);
            case BigDecimal number -> number;
            case Double number -> BigDecimal.valueOf(number);
            default -> throw new InvalidConfigException(pathOf(key) + " must be an amount such as 3.50, was " + value);
        };
        try {
            return Money.of(amount, currency);
        } catch (ArithmeticException e) {
            throw new InvalidConfigException(pathOf(key) + " has more than 9 decimal places: " + amount, e);
        }
    }

    Currency currency(String key, Currency fallback) {
        String code = optionalText(key);
        if (code == null) {
            return fallback;
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvalidConfigException(pathOf(key) + " must be an ISO 4217 currency code, was " + code, e);
        }
    }

    <T> List<T> sectionList(String key, Function<Section, T> reader) {
        List<T> sections = new ArrayList<>();
        List<?> items = list(key);
        for (int i = 0; i < items.size(); i++) {
            String childPath = pathOf(key) + "[" + i + "]";
            sections.add(reader.apply(new Section(childPath, asMap(childPath, items.get(i)))));
        }
        return sections;
    }

    <T> T build(Supplier<T> constructor) {
        try {
            return constructor.get();
        } catch (IllegalArgumentException e) {
            String message = String.valueOf(e.getMessage());
            throw new InvalidConfigException(path.isEmpty() ? message : path + ": " + message, e);
        }
    }

    private List<?> list(String key) {
        return listAt(pathOf(key), values.get(key));
    }

    private static List<?> listAt(String path, @Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InvalidConfigException(path + " must be a list");
        }
        return list;
    }

    private static List<String> textsAt(String path, @Nullable Object value) {
        List<String> texts = new ArrayList<>();
        List<?> items = listAt(path, value);
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof String text) || text.isBlank()) {
                throw new InvalidConfigException(path + "[" + i + "] must be a non-empty string");
            }
            texts.add(text);
        }
        return texts;
    }

    private static Map<String, ?> asMap(String path, @Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new InvalidConfigException(path + " must be a mapping");
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new InvalidConfigException(path + " has a key that is not a string: " + entry.getKey());
            }
            entries.put(name, entry.getValue());
        }
        return entries;
    }

    private String pathOf(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
