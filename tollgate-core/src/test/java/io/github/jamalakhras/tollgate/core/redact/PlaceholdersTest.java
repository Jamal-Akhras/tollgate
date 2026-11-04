package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    private static final Placeholders ISSUED = new Placeholders(Map.of(
            "[EMAIL_1]", "jane@example.com",
            "[EMAIL_12]", "bob@example.org",
            "[SECRET_1]", "line one\nsaid \"hi\" \\ \u0001",
            "[NATIONAL_ID_1]", "AB 12 34 56 C"));

    @Test
    void restoresOnlyThePlaceholdersItIssued() {
        assertThat(ISSUED.restore("To [EMAIL_1] and [EMAIL_12], not [EMAIL_2] or [EMAIL_1 or [email_1]"))
                .isEqualTo("To jane@example.com and bob@example.org, not [EMAIL_2] or [EMAIL_1 or [email_1]");
        assertThat(ISSUED.restore("[NATIONAL_ID_1][EMAIL_1]")).isEqualTo("AB 12 34 56 Cjane@example.com");
    }

    @Test
    void leavesTextWithoutPlaceholdersAlone() {
        String text = "No brackets here";

        assertThat(ISSUED.restore(text)).isSameAs(text);
        assertThat(Placeholders.NONE.restore("[EMAIL_1]")).isEqualTo("[EMAIL_1]");
        assertThat(Placeholders.NONE.isEmpty()).isTrue();
        assertThat(ISSUED.isEmpty()).isFalse();
    }

    @Test
    void restoresValuesWithReplacementCharactersLiterally() {
        Placeholders placeholders = new Placeholders(Map.of("[SECRET_1]", "a$1b\\c"));

        assertThat(placeholders.restore("x [SECRET_1] y")).isEqualTo("x a$1b\\c y");
    }

    @Test
    void escapesValuesRestoredInsideJson() {
        assertThat(ISSUED.restoreEncoded("{\"to\":\"[EMAIL_1]\",\"note\":\"[SECRET_1]\"}"))
                .isEqualTo("{\"to\":\"jane@example.com\",\"note\":\"line one\\nsaid \\\"hi\\\" \\\\ \\u0001\"}");
        Placeholders whitespace = new Placeholders(Map.of("[SECRET_1]", "a\tb\rc"));
        assertThat(whitespace.restoreEncoded("[SECRET_1]")).isEqualTo("a\\tb\\rc");
    }

    @Test
    void holdsBackOnlyWhatCouldStillBecomeAPlaceholder() {
        assertThat(ISSUED.heldFrom("Write to [")).isEqualTo(9);
        assertThat(ISSUED.heldFrom("Write to [EMAIL_1")).isEqualTo(9);
        assertThat(ISSUED.heldFrom("Write to [EMAIL_1]")).isEqualTo(18);
        assertThat(ISSUED.heldFrom("Write to [EMAIL_3")).isEqualTo(17);
        assertThat(ISSUED.heldFrom("A list [1, 2")).isEqualTo(12);
        assertThat(ISSUED.heldFrom("[EMAIL_1 [")).isEqualTo(9);
        assertThat(ISSUED.heldFrom("plain")).isEqualTo(5);
        assertThat(Placeholders.NONE.heldFrom("[")).isEqualTo(1);
    }

    @Test
    void restoresAStreamSplitAtEveryPoint() {
        String text = "Hi [EMAIL_1], meet [EMAIL_12] [NATIONAL_ID_1]; ignore [EMAIL_9] and [x";
        String expected = ISSUED.restore(text);
        for (int first = 0; first <= text.length(); first++) {
            for (int second = first; second <= text.length(); second++) {
                StreamedText streamed = ISSUED.streamed(false);
                String joined = streamed.next(text.substring(0, first))
                        + streamed.next(text.substring(first, second))
                        + streamed.next(text.substring(second))
                        + streamed.flush();
                assertThat(joined).as("split at %d and %d", first, second).isEqualTo(expected);
            }
        }
    }

    @Test
    void restoresAStreamOneCharacterAtATime() {
        String text = "[EMAIL_1][EMAIL_12][[EMAIL_1]] [SECRET_1]";
        StreamedText streamed = ISSUED.streamed(true);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            joined.append(streamed.next(text.substring(i, i + 1)));
        }
        joined.append(streamed.flush());

        assertThat(joined.toString()).isEqualTo(ISSUED.restoreEncoded(text));
    }

    @Test
    void releasesHeldTextWhenTheStreamEnds() {
        StreamedText streamed = ISSUED.streamed(false);

        assertThat(streamed.next("Write to [EMA")).isEqualTo("Write to ");
        assertThat(streamed.flush()).isEqualTo("[EMA");
        assertThat(streamed.flush()).isEmpty();
        assertThat(streamed.next("IL_1]")).isEqualTo("IL_1]");
    }
}
