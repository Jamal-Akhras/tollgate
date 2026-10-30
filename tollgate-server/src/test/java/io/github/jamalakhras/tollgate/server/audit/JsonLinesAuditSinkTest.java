package io.github.jamalakhras.tollgate.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonLinesAuditSinkTest {

    private static final Clock CLOCK = Clock.fixed(Samples.TIME, ZoneOffset.UTC);

    @TempDir
    private Path directory;

    private void run(String... ids) throws IOException {
        try (AuditLog log = AuditLog.start(JsonLinesAuditSink.open(directory), Samples.KEYS, CLOCK, true)) {
            for (String id : ids) {
                log.append(Samples.intent(id));
                log.append(Samples.outcome(id));
            }
        }
    }

    private TrailVerifier.Report verify() throws IOException {
        return AuditDirectory.verify(directory, AuditKeys.publicKey(Samples.NEW_KEYS.publicKey()));
    }

    @Test
    void writesEachRunToItsOwnFileAndChainsThemTogether() throws IOException {
        run("a", "b");
        run("c");

        assertThat(AuditDirectory.files(directory))
                .extracting(path -> path.getFileName().toString())
                .containsExactly("tollgate-audit-00000001.jsonl", "tollgate-audit-00000002.jsonl");
        TrailVerifier.Report report = verify();
        assertThat(report.failures()).isEmpty();
        assertThat(report.warnings()).isEmpty();
        assertThat(report.records()).isEqualTo(6);
        assertThat(report.batches()).isEqualTo(2);
    }

    @Test
    void createsNoFileForARunThatWroteNothing() throws IOException {
        run();

        assertThat(AuditDirectory.files(directory)).isEmpty();
        assertThat(verify().records()).isZero();
    }

    @Test
    void letsOnlyOneWriterUseADirectory() throws IOException {
        try (JsonLinesAuditSink first = JsonLinesAuditSink.open(directory)) {
            assertThat(first.trail()).hasFileName("tollgate-audit-00000001.jsonl");
            assertThatThrownBy(() -> JsonLinesAuditSink.open(directory))
                    .isInstanceOf(IOException.class)
                    .hasMessageStartingWith("Another Tollgate is already writing to the audit directory");
        }
        JsonLinesAuditSink.open(directory).close();
    }

    @Test
    void reportsATornLineAndContinuesAfterIt() throws IOException {
        run("a");
        Path first = AuditDirectory.files(directory).getFirst();
        Files.writeString(first, "{\"v\":1,\"type\":\"int", StandardOpenOption.APPEND);
        run("b");

        TrailVerifier.Report report = verify();

        assertThat(report.failures()).isEmpty();
        assertThat(report.warnings())
                .extracting(TrailVerifier.Problem::toString)
                .containsExactly("tollgate-audit-00000001.jsonl:4: "
                        + "The last line is incomplete, as a crash during a write leaves it");
        assertThat(report.batches()).isEqualTo(2);
    }

    @Test
    void reportsEditedAndUnreadableLines() throws IOException {
        run("a", "b");
        Path file = AuditDirectory.files(directory).getFirst();
        List<String> lines = Files.readAllLines(file);
        lines.set(0, lines.get(0).replace("support-bot", "billing"));
        lines.set(1, "not json");
        Files.write(file, lines);

        assertThat(verify().failures())
                .extracting(TrailVerifier.Problem::toString)
                .containsExactly(
                        "tollgate-audit-00000001.jsonl:2: Not valid JSON: Unrecognized token 'not': was expecting "
                                + "(JSON String, Number, Array, Object or token 'null', 'true' or 'false')",
                        "tollgate-audit-00000001.jsonl:5: Batch 1 counts 4 records, but 3 precede it");
    }

    @Test
    void treatsBytesThatAreNotUtf8AsATamperedRecord() throws IOException {
        run("a");
        Path file = AuditDirectory.files(directory).getFirst();
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        int at = text.indexOf("support-bot");
        bytes[at] = (byte) 0xff;
        Files.write(file, bytes);

        assertThat(verify().intact()).isFalse();
    }

    @Test
    void ignoresOtherFilesInTheDirectory() throws IOException {
        run("a");
        Files.writeString(directory.resolve("notes.txt"), "not part of the trail");

        assertThat(verify().intact()).isTrue();
    }
}
