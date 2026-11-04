package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostileInputTest {

    private static final int ONE_MEGABYTE = 1 << 20;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a",
                "1",
                "1 ",
                "1-",
                "a@",
                "a.",
                "a@b.",
                "+1 ",
                "+1(",
                "(1) ",
                "0 ",
                "1.",
                ":",
                "a:",
                "1::",
                "GB82",
                "AB 12 ",
                "sk-",
                "-----BEGIN PRIVATE KEY-----",
                "[",
                "[EMAIL_",
                "😀"
            })
    void redactsAMegabyteOfRepeatedPatternQuickly(String unit) {
        String text = unit.repeat(ONE_MEGABYTE / unit.length());
        Redactor redactor = new Redactor(Set.of(), EnumSet.allOf(Detector.class));

        String redacted = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> redactor.redact(text));

        assertThat(redactor.placeholders().restore(redacted)).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@", "a.", "a@b.c"})
    void handlesALongDomain(String start) {
        String text = start + "a.".repeat(ONE_MEGABYTE / 2) + "com";
        Redactor redactor = new Redactor(Set.of(), EnumSet.allOf(Detector.class));

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> redactor.redact(text));
    }
}
