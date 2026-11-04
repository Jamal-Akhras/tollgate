package io.github.jamalakhras.tollgate.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AuditJsonTest {

    private static final Seal SEAL = new Seal(
            61,
            214,
            Samples.TIME,
            "0".repeat(64),
            "9f2c" + "4".repeat(60),
            "b81e44a0c3f2d917",
            "4c07a2e19b5d3f60",
            "c2lnbmF0dXJl");

    static Stream<AuditEntry> entries() {
        return Stream.of(
                Samples.intent("a"),
                Samples.refused("b"),
                Samples.redacted("d"),
                Samples.outcome("a"),
                Samples.unreachable("c"),
                SEAL);
    }

    @ParameterizedTest
    @MethodSource("entries")
    void readsBackWhatItWrites(AuditEntry entry) {
        String line = AuditJson.write(entry);

        assertThat(line).doesNotContain("\n");
        assertThat(AuditJson.read(line)).isEqualTo(entry);
    }

    @Test
    void writesAnIntentInTheDocumentedShape() {
        assertThat(AuditJson.write(Samples.refused("7c0e")))
                .isEqualTo("{\"v\":1,\"type\":\"intent\",\"id\":\"7c0e\",\"time\":\"2026-11-02T14:03:10.512Z\","
                        + "\"tenant\":\"support-bot\",\"key\":\"k7Qm2xPa4bZr\",\"endpoint\":\"openai\",\"model\":\"gpt\","
                        + "\"provider\":\"openai\",\"upstream\":\"gpt-5\",\"dataClass\":null,"
                        + "\"configVersion\":\"3f1c" + "0".repeat(60) + "\",\"requestHmac\":\"3f1c" + "0".repeat(60)
                        + "\",\"steps\":[{\"guard\":\"data-class\",\"decision\":{\"verdict\":\"deny\","
                        + "\"refusal\":\"policy_denied\",\"message\":\"Confidential data stays on local models\"},"
                        + "\"nanos\":1800}],\"decision\":{\"verdict\":\"deny\",\"refusal\":\"policy_denied\","
                        + "\"message\":\"Confidential data stays on local models\"},\"reservation\":null}");
        assertThat(AuditJson.write(Samples.outcome("7c0e")))
                .contains("\"cost\":{\"amount\":\"0.006996000\",\"currency\":\"USD\"}");
    }

    @Test
    void writesARedactedIntentAsVersion2() {
        String line = AuditJson.write(Samples.redacted("7c0e"));

        assertThat(line)
                .startsWith("{\"v\":2,\"type\":\"intent\",")
                .endsWith(",\"redactions\":{\"values\":{\"card\":1,\"email\":2},\"sentHmac\":\"e7a3" + "5".repeat(60)
                        + "\"}}");
        assertThat(AuditJson.write(Samples.intent("7c0e")))
                .startsWith("{\"v\":1,")
                .doesNotContain("redactions");
    }

    @Test
    void keepsRedactionsAndTheirVersionTogether() {
        String redacted = AuditJson.write(Samples.redacted("a"));
        String plain = AuditJson.write(Samples.intent("a"));

        assertThatThrownBy(() -> AuditJson.read(redacted.replace("{\"v\":2,", "{\"v\":1,")))
                .hasMessage("'redactions' is not part of the line");
        assertThatThrownBy(() -> AuditJson.read(plain.replace("{\"v\":1,", "{\"v\":2,")))
                .hasMessage("'redactions' is missing from the line");
        assertThatThrownBy(() ->
                        AuditJson.read(AuditJson.write(Samples.outcome("a")).replace("{\"v\":1,", "{\"v\":2,")))
                .hasMessage("Unsupported audit record version 2");
        assertThatThrownBy(() -> AuditJson.read(redacted.replace("\"email\":2", "\"email\":0")))
                .hasMessage("redactions.email must be at least 1");
        assertThatThrownBy(() -> AuditJson.read(redacted.replace("\"email\":2", "\"email\":\"2\"")))
                .hasMessage("'email' must be a whole number");
        assertThatThrownBy(() -> AuditJson.read(redacted.replace(",\"sentHmac\"", ",\"note\":1,\"sentHmac\"")))
                .hasMessage("'note' is not part of 'redactions'");
        assertThatThrownBy(
                        () -> AuditJson.read(redacted.replace("\"values\":{\"card\":1,\"email\":2}", "\"values\":{}")))
                .hasMessage("redactions must count at least one value");
    }

    @Test
    void rejectsFieldsTheHashDoesNotCover() {
        String line = AuditJson.write(SEAL).replace("{\"v\":1,", "{\"v\":1,\"note\":\"trust me\",");

        assertThatThrownBy(() -> AuditJson.read(line)).hasMessage("'note' is not part of the line");
    }

    static Stream<Arguments> brokenLines() {
        return Stream.of(
                Arguments.of("{\"v\":1,\"type\":\"seal\"}", "'batch' is missing from the line"),
                Arguments.of("{\"v\":2,\"type\":\"seal\"}", "Unsupported audit record version 2"),
                Arguments.of("{\"v\":1,\"type\":\"note\"}", "Unknown line type 'note'"),
                Arguments.of("{\"v\":1,\"v\":1}", "Not valid JSON: Duplicate Object property \"v\""),
                Arguments.of("[1]", "the line must be a JSON object"),
                Arguments.of("{\"v\":\"1\"}", "'v' must be a whole number"));
    }

    @ParameterizedTest
    @MethodSource("brokenLines")
    void explainsWhatIsWrongWithALine(String line, String problem) {
        assertThatThrownBy(() -> AuditJson.read(line)).hasMessage(problem);
    }

    @Test
    void rejectsValuesTheRecordsDoNotAllow() {
        String shortRoot = AuditJson.write(SEAL).replace("9f2c", "9f2");
        String badTime = AuditJson.write(SEAL).replace("2026-11-02T14:03:10.512Z", "yesterday");
        String unknownRefusal = AuditJson.write(Samples.refused("b")).replace("policy_denied", "vibes");
        String fractionalCent = AuditJson.write(Samples.outcome("a")).replace("0.006996000", "0.0069960001");

        assertThatThrownBy(() -> AuditJson.read(shortRoot)).hasMessage("root must be 64 lowercase hex characters");
        assertThatThrownBy(() -> AuditJson.read(badTime)).hasMessage("'time' is not a time: yesterday");
        assertThatThrownBy(() -> AuditJson.read(unknownRefusal)).hasMessageContaining("Unknown refusal: vibes");
        assertThatThrownBy(() -> AuditJson.read(fractionalCent))
                .hasMessage("'amount' must be a number with at most 9 decimal places: 0.0069960001");
    }
}
