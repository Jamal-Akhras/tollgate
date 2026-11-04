package io.github.jamalakhras.tollgate.core.redact;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class Detector implements Comparable<Detector> {

    public static final Detector SECRET =
            builtIn(0, "secret", "a secret such as an API key or private key", Patterns::secrets);
    public static final Detector EMAIL = builtIn(1, "email", "an email address", Patterns::emails);
    public static final Detector CARD = builtIn(2, "card", "a card number", Patterns::cards);
    public static final Detector IBAN = builtIn(3, "iban", "an IBAN", Patterns::ibans);
    public static final Detector NATIONAL_ID = builtIn(4, "national-id", "a national ID number", Patterns::nationalIds);
    public static final Detector PHONE = builtIn(5, "phone", "a phone number", Patterns::phones);
    public static final Detector IP = builtIn(6, "ip", "an IP address", Patterns::ips);

    public static final List<Detector> BUILT_IN = List.of(SECRET, EMAIL, CARD, IBAN, NATIONAL_ID, PHONE, IP);

    private static final int CUSTOM = 7;

    private final int rank;
    private final String name;
    private final String label;
    private final String description;
    private final Finder finder;

    private Detector(int rank, String name, String description, Finder finder) {
        this.rank = rank;
        this.name = name;
        this.label = name.toUpperCase(Locale.ROOT).replaceAll("[.:-]", "_");
        this.description = description;
        this.finder = finder;
    }

    private static Detector builtIn(int rank, String name, String description, BiConsumer<String, List<Span>> finder) {
        return new Detector(rank, name, description, (text, spans, budget) -> finder.accept(text, spans));
    }

    public static Optional<Detector> builtIn(String name) {
        return BUILT_IN.stream().filter(detector -> detector.name.equals(name)).findFirst();
    }

    public static String builtInNames() {
        return BUILT_IN.stream().map(Detector::configName).collect(Collectors.joining(", "));
    }

    public static Detector pattern(String name, String regex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "pattern is not a valid regular expression: " + e.getDescription() + " near index " + e.getIndex(),
                    e);
        }
        if (pattern.matcher("").matches()) {
            throw new IllegalArgumentException("pattern matches empty text, so it would match everywhere");
        }
        return new Detector(CUSTOM, name, matching(name), new CustomPattern(name, pattern));
    }

    public static Detector terms(String name, List<String> terms, boolean caseSensitive) {
        return new Detector(CUSTOM, name, matching(name), new Terms(terms, caseSensitive));
    }

    private static String matching(String name) {
        return "a value matching the '" + name + "' detector";
    }

    public String configName() {
        return name;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    void find(String text, List<Span> spans, ScanBudget budget) {
        finder.find(text, spans, budget);
    }

    @Override
    public int compareTo(Detector other) {
        return rank != other.rank ? Integer.compare(rank, other.rank) : name.compareTo(other.name);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Detector detector && detector.name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }

    interface Finder {

        void find(String text, List<Span> spans, ScanBudget budget);
    }
}
