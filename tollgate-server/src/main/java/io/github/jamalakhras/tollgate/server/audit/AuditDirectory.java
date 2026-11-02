package io.github.jamalakhras.tollgate.server.audit;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class AuditDirectory {

    private static final Pattern FILE_NAME = Pattern.compile("tollgate-audit-(\\d{8})\\.jsonl");

    private AuditDirectory() {}

    public static List<Path> files(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path ->
                            FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }
    }

    public static TrailVerifier.Report verify(Path directory, PublicKey publicKey) throws IOException {
        TrailVerifier verifier = new TrailVerifier(publicKey);
        for (Path file : files(directory)) {
            verifier.startFile(file.getFileName().toString());
            read(file, (number, text, torn) -> {
                if (torn) {
                    verifier.tornLine(number);
                    return;
                }
                try {
                    verifier.entry(number, AuditJson.read(text));
                } catch (IllegalArgumentException e) {
                    verifier.unreadable(number, String.valueOf(e.getMessage()));
                }
            });
        }
        return verifier.finish();
    }

    public static void records(Path directory, Consumer<AuditRecord> each) throws IOException {
        for (Path file : files(directory)) {
            read(file, (number, text, torn) -> {
                if (torn) {
                    return;
                }
                AuditEntry entry;
                try {
                    entry = AuditJson.read(text);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "Line " + number + " of " + file + " cannot be read; run audit verify to check the trail",
                            e);
                }
                if (entry instanceof AuditRecord record) {
                    each.accept(record);
                }
            });
        }
    }

    static Optional<Seal> lastSeal(Path directory) throws IOException {
        for (Path file : files(directory).reversed()) {
            AtomicReference<@Nullable Seal> last = new AtomicReference<>();
            read(file, (number, text, torn) -> {
                if (!torn) {
                    sealIn(text).ifPresent(last::set);
                }
            });
            Seal seal = last.get();
            if (seal != null) {
                return Optional.of(seal);
            }
        }
        return Optional.empty();
    }

    static Path nextFile(Path directory) throws IOException {
        int last = 0;
        for (Path file : files(directory)) {
            Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
            if (matcher.matches()) {
                last = Math.max(last, Integer.parseInt(matcher.group(1)));
            }
        }
        return directory.resolve("tollgate-audit-%08d.jsonl".formatted(last + 1));
    }

    private static void read(Path file, Lines lines) throws IOException {
        boolean torn = !endsWithNewline(file);
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            long number = 0;
            String line = reader.readLine();
            while (line != null) {
                String next = reader.readLine();
                number++;
                lines.line(number, line, next == null && torn);
                line = next;
            }
        }
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() == 0) {
                return true;
            }
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.read(last, channel.size() - 1);
            return last.get(0) == '\n';
        }
    }

    private static Optional<Seal> sealIn(String text) {
        try {
            return AuditJson.read(text) instanceof Seal seal ? Optional.of(seal) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private interface Lines {

        void line(long number, String text, boolean torn);
    }
}
