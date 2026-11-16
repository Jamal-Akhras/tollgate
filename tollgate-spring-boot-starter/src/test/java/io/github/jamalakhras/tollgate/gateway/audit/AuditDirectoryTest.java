package io.github.jamalakhras.tollgate.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditDirectoryTest {

    @TempDir
    Path directory;

    private final List<String> ids = new ArrayList<>();

    private void collect(AuditEntry entry) {
        ids.add(((AuditRecord) entry).id());
    }

    private static String line(String id) {
        return AuditJson.write(Samples.intent(id)) + "\n";
    }

    private Path file(String content) throws IOException {
        Path file = directory.resolve("tollgate-audit-00000001.jsonl");
        Files.writeString(file, content);
        return file;
    }

    private static void append(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    @Test
    void readsNothingFromAnEmptyFile() throws IOException {
        Path file = file("");

        assertThat(AuditDirectory.follow(file, 0, 1024, this::collect)).isZero();
        assertThat(ids).isEmpty();
    }

    @Test
    void returnsTheSizeAfterReadingWholeLines() throws IOException {
        Path file = file(line("a") + line("b"));

        assertThat(AuditDirectory.follow(file, 0, 1 << 20, this::collect)).isEqualTo(Files.size(file));
        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void leavesATornLastLineForTheNextCallAndReadsItOnceItIsCompleted() throws IOException {
        String torn = line("b");
        Path file = file(line("a") + torn.substring(0, 20));
        long first = AuditDirectory.follow(file, 0, 1 << 20, this::collect);

        assertThat(first).isEqualTo(line("a").length());
        assertThat(ids).containsExactly("a");

        append(file, torn.substring(20));
        long second = AuditDirectory.follow(file, first, 1 << 20, this::collect);

        assertThat(second).isEqualTo(Files.size(file));
        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void continuesFromAReturnedOffsetWithoutRepeatingARecord() throws IOException {
        Path file = file(line("a"));
        long first = AuditDirectory.follow(file, 0, 1 << 20, this::collect);
        append(file, line("b"));
        AuditDirectory.follow(file, first, 1 << 20, this::collect);

        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void stopsAtMaxBytesOnALineBoundary() throws IOException {
        int one = line("a").length();
        Path file = file(line("a") + line("b") + line("c"));

        long offset = AuditDirectory.follow(file, 0, one * 2 + one / 2, this::collect);

        assertThat(offset).isEqualTo(one * 2L);
        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void skipsALineThatIsNotARecordAndBlankLines() throws IOException {
        Path file = file(line("a") + "not json\n\n" + line("b"));

        assertThat(AuditDirectory.follow(file, 0, 1 << 20, this::collect)).isEqualTo(Files.size(file));
        assertThat(ids).containsExactly("a", "b");
    }

    @Test
    void movesPastAChunkWithNoNewline() throws IOException {
        Path file = file("x".repeat(100) + "\n");

        assertThat(AuditDirectory.follow(file, 0, 50, this::collect)).isEqualTo(50);
        assertThat(ids).isEmpty();
    }

    @Test
    void refusesAnOffsetPastTheEndOfTheFile() throws IOException {
        Path file = file(line("a"));

        assertThatThrownBy(() -> AuditDirectory.follow(file, Files.size(file) + 1, 1024, this::collect))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
