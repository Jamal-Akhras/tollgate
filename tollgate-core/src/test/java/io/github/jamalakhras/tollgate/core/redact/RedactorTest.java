package io.github.jamalakhras.tollgate.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RedactorTest {

    private static final Set<Detector> ALL = EnumSet.allOf(Detector.class);

    @Test
    void replacesEachValueWithANumberedPlaceholder() {
        Redactor redactor = new Redactor(Set.of(), ALL);

        String redacted = redactor.redact("Mail jane@example.com or bob@example.org, card 4111 1111 1111 1111.");

        assertThat(redacted).isEqualTo("Mail [EMAIL_1] or [EMAIL_2], card [CARD_1].");
        assertThat(redactor.values()).isEqualTo(Map.of(Detector.EMAIL, 2, Detector.CARD, 1));
        assertThat(redactor.refused()).isEmpty();
    }

    @Test
    void givesTheSameValueTheSamePlaceholderAcrossTexts() {
        Redactor redactor = new Redactor(Set.of(), ALL);

        assertThat(redactor.redact("From jane@example.com")).isEqualTo("From [EMAIL_1]");
        assertThat(redactor.redact("To bob@example.org, cc jane@example.com")).isEqualTo("To [EMAIL_2], cc [EMAIL_1]");
        assertThat(redactor.redact("jane@example.com")).isEqualTo("[EMAIL_1]");
        assertThat(redactor.values()).isEqualTo(Map.of(Detector.EMAIL, 2));
    }

    @Test
    void numbersByFirstAppearanceSoAConversationRedactsTheSameWayEachTurn() {
        String first = "I am jane@example.com, call +44 20 7946 0958";
        String reply = "Thanks, I will write to jane@example.com";
        String second = "Also cc bob@example.org";

        Redactor turnOne = new Redactor(Set.of(), ALL);
        String firstRedacted = turnOne.redact(first);
        Redactor turnTwo = new Redactor(Set.of(), ALL);

        assertThat(turnTwo.redact(first)).isEqualTo(firstRedacted);
        assertThat(turnTwo.redact(reply)).isEqualTo("Thanks, I will write to [EMAIL_1]");
        assertThat(turnTwo.redact(second)).isEqualTo("Also cc [EMAIL_2]");
    }

    @Test
    void onlyRedactsWithTheDetectorsItWasGiven() {
        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.CARD));

        assertThat(redactor.redact("jane@example.com paid with 4111111111111111"))
                .isEqualTo("jane@example.com paid with [CARD_1]");
    }

    @Test
    void returnsTextWithNothingToRedactUnchanged() {
        String text = "Nothing personal here.";

        assertThat(new Redactor(Set.of(), ALL).redact(text)).isSameAs(text);
        assertThat(new Redactor(Set.of(), Set.of()).redact("jane@example.com")).isEqualTo("jane@example.com");
    }

    @Test
    void prefersTheLongerOfTwoValuesThatStartTogether() {
        Redactor redactor = new Redactor(Set.of(), ALL);

        assertThat(redactor.redact("Write to 4111111111111111@example.com")).isEqualTo("Write to [EMAIL_1]");
        assertThat(redactor.redact("Or +4420794609581@example.com")).isEqualTo("Or [EMAIL_2]");
        assertThat(redactor.values()).isEqualTo(Map.of(Detector.EMAIL, 2));
    }

    @Test
    void notesTheFirstRefusedDetectorItFinds() {
        Redactor redactor = new Redactor(EnumSet.of(Detector.SECRET, Detector.CARD), Set.of(Detector.EMAIL));

        assertThat(redactor.redact("jane@example.com")).isEqualTo("[EMAIL_1]");
        assertThat(redactor.refused()).isEmpty();
        assertThat(redactor.redact("card 4111111111111111 and key " + DetectorTest.OPENAI_KEY))
                .isEqualTo("card 4111111111111111 and key " + DetectorTest.OPENAI_KEY);
        assertThat(redactor.refused()).contains(Detector.SECRET);
        redactor.redact("only 4111111111111111");
        assertThat(redactor.refused()).contains(Detector.SECRET);
    }

    @Test
    void refusesEvenWhenARedactedValueOverlapsTheRefusedOne() {
        Redactor redactor = new Redactor(Set.of(Detector.CARD), Set.of(Detector.EMAIL));

        assertThat(redactor.redact("4111111111111111@example.com")).isEqualTo("[EMAIL_1]");
        assertThat(redactor.refused()).contains(Detector.CARD);
    }

    @Test
    void restoresWhatItReplaced() {
        Redactor redactor = new Redactor(Set.of(), ALL);
        String text = "jane@example.com, 8.8.8.8, GB82 WEST 1234 5698 7654 32, AB 12 34 56 C, +44 20 7946 0958, "
                + DetectorTest.ANTHROPIC_KEY + "\n" + DetectorTest.PRIVATE_KEY;

        String redacted = redactor.redact(text);

        assertThat(redacted)
                .isEqualTo("[EMAIL_1], [IP_1], [IBAN_1], [NATIONAL_ID_1], [PHONE_1], [SECRET_1]\n[SECRET_2]");
        assertThat(redactor.placeholders().restore(redacted)).isEqualTo(text);
    }

    @Test
    void replacesPlaceholdersTheUserWroteSoTheyComeBackAsWritten() {
        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.EMAIL));
        String text = "Template says [EMAIL_1], mine is jane@example.com, [CARD_1] and [EMAIL_9] stay";

        String redacted = redactor.redact(text);

        assertThat(redacted).isEqualTo("Template says [EMAIL_1], mine is [EMAIL_2], [CARD_1] and [EMAIL_3] stay");
        assertThat(redactor.values()).isEqualTo(Map.of(Detector.EMAIL, 1));
        assertThat(redactor.placeholders().restore("Reply to [EMAIL_2] about [EMAIL_1] and [EMAIL_3]"))
                .isEqualTo("Reply to jane@example.com about [EMAIL_1] and [EMAIL_9]");
    }
}
