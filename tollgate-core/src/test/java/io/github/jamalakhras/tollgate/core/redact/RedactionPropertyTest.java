package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class RedactionPropertyTest {

    private static final Set<Detector> ALL = Stream.concat(
                    Detector.BUILT_IN.stream(),
                    Stream.of(
                            Detector.pattern("customer-id", "CUST-[0-9]{6}"),
                            Detector.pattern("account", "account (?<value>[0-9]{8})"),
                            Detector.terms("codenames", List.of("Bluebird", "Project Falcon"), false)))
            .collect(Collectors.toSet());
    private static final List<String> VALUES = List.of(
            "jane@example.com",
            "bob.smith+x@mail.example.co.uk",
            "4111 1111 1111 1111",
            "5555555555554444",
            "GB82 WEST 1234 5698 7654 32",
            "DE89370400440532013000",
            "AB 12 34 56 C",
            "123-45-6789",
            "+44 20 7946 0958",
            "(415) 555-2671",
            "8.8.8.8",
            "2a00:1450:4009:81f::200e",
            DetectorTest.OPENAI_KEY,
            DetectorTest.PRIVATE_KEY,
            "CUST-004211",
            "Project   Falcon",
            "bluebird");
    private static final List<String> FILLER = List.of(
            "Hello",
            "the",
            "order 1234",
            "v1.2.3",
            "[note]",
            "[EMAIL_1]",
            "2026-11-03",
            "a-b",
            "x@y",
            "é",
            "😀",
            "",
            "[",
            "]",
            "10:30",
            "+",
            "-----",
            "account 12345678",
            "[CUSTOMER_ID_1]",
            "Bluebirds");

    @Provide
    Arbitrary<String> conversations() {
        Arbitrary<String> part = Arbitraries.oneOf(Arbitraries.of(VALUES), Arbitraries.of(FILLER));
        Arbitrary<String> separator = Arbitraries.of(" ", ", ", "\n", " (", ") ", ": ", ". ");
        return Combinators.combine(part, separator)
                .as(String::concat)
                .list()
                .ofMaxSize(30)
                .map(parts -> String.join("", parts));
    }

    @Provide
    Arbitrary<String> awkward() {
        return Arbitraries.strings()
                .withChars("0123456789 .-+@:[]_()ABEGKLNTZabcdefxyz\n")
                .ofMaxLength(120);
    }

    @Property(tries = 2_000)
    void restoringUndoesRedacting(@ForAll("conversations") String text) {
        Redactor redactor = new Redactor(Set.of(), ALL);

        String redacted = redactor.redact(text);

        assertThat(redactor.placeholders().restore(redacted)).isEqualTo(text);
    }

    @Property(tries = 2_000)
    void redactedTextHasNothingLeftToFind(@ForAll("conversations") String text) {
        String redacted = new Redactor(Set.of(), ALL).redact(text);

        for (String value : VALUES) {
            assertThat(redacted).doesNotContain(value);
        }
        assertThat(new Redactor(Set.of(), ALL).redact(redacted)).isEqualTo(redacted);
    }

    @Property(tries = 5_000)
    void anyTextSurvivesARoundTrip(@ForAll("awkward") String text) {
        Redactor redactor = new Redactor(Set.of(), ALL);

        String redacted = redactor.redact(text);

        assertThat(redactor.placeholders().restore(redacted)).isEqualTo(text);
    }

    @Property(tries = 2_000)
    void aStreamRestoresLikeTheWhole(
            @ForAll("conversations") String text,
            @ForAll @Size(max = 8) List<@IntRange(min = 0, max = 2_000) Integer> cuts,
            @ForAll boolean encoded) {
        Redactor redactor = new Redactor(Set.of(), ALL);
        String redacted = redactor.redact(text);
        Placeholders placeholders = redactor.placeholders();
        List<Integer> points = new ArrayList<>();
        cuts.forEach(cut -> points.add(Math.min(cut, redacted.length())));
        points.add(redacted.length());
        points.sort(null);

        StreamedText streamed = placeholders.streamed(encoded);
        StringBuilder joined = new StringBuilder();
        int from = 0;
        for (int point : points) {
            joined.append(streamed.next(redacted.substring(from, point)));
            from = point;
        }
        joined.append(streamed.flush());

        assertThat(joined.toString())
                .isEqualTo(encoded ? placeholders.restoreEncoded(redacted) : placeholders.restore(redacted));
    }
}
