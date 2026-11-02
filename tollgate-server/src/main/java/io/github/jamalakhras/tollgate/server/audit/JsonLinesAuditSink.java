package io.github.jamalakhras.tollgate.server.audit;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditSink;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class JsonLinesAuditSink implements AuditSink {

    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE = PosixFilePermissions.fromString("rw-------");

    private final Path directory;
    private final FileChannel lockFile;
    private final FileLock lock;
    private final Optional<Seal> lastSeal;
    private final Path trail;
    private @Nullable FileChannel channel;

    private JsonLinesAuditSink(
            Path directory, FileChannel lockFile, FileLock lock, Optional<Seal> lastSeal, Path trail) {
        this.directory = directory;
        this.lockFile = lockFile;
        this.lock = lock;
        this.lastSeal = lastSeal;
        this.trail = trail;
    }

    public static JsonLinesAuditSink open(Path directory) throws IOException {
        Files.createDirectories(directory);
        FileChannel lockFile =
                FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = lockFile.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockFile.close();
            throw new IOException("Another Tollgate is already writing to the audit directory " + directory);
        }
        try {
            keepPrivate(directory);
            return new JsonLinesAuditSink(
                    directory, lockFile, lock, AuditDirectory.lastSeal(directory), AuditDirectory.nextFile(directory));
        } catch (IOException | RuntimeException e) {
            lockFile.close();
            throw e;
        }
    }

    @Override
    public Optional<Seal> lastSeal() {
        return lastSeal;
    }

    @Override
    public void write(List<AuditEntry> entries) throws IOException {
        StringBuilder lines = new StringBuilder();
        for (AuditEntry entry : entries) {
            lines.append(AuditJson.write(entry)).append('\n');
        }
        ByteBuffer bytes = ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8));
        FileChannel out = channel();
        while (bytes.hasRemaining()) {
            out.write(bytes);
        }
        out.force(true);
    }

    @Override
    public void close() throws IOException {
        try (lockFile) {
            if (channel != null) {
                channel.close();
            }
            lock.release();
        }
    }

    Path trail() {
        return trail;
    }

    private FileChannel channel() throws IOException {
        FileChannel open = channel;
        if (open == null) {
            open = FileChannel.open(trail, StandardOpenOption.CREATE_NEW, StandardOpenOption.APPEND);
            if (hasPermissions(trail)) {
                Files.setPosixFilePermissions(trail, PRIVATE_FILE);
            }
            forceDirectory();
            channel = open;
        }
        return open;
    }

    private static void keepPrivate(Path directory) throws IOException {
        if (!hasPermissions(directory)) {
            return;
        }
        Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY);
        Files.setPosixFilePermissions(directory.resolve(".lock"), PRIVATE_FILE);
        for (Path file : AuditDirectory.files(directory)) {
            Files.setPosixFilePermissions(file, PRIVATE_FILE);
        }
    }

    private static boolean hasPermissions(Path path) {
        return path.getFileSystem().supportedFileAttributeViews().contains("posix");
    }

    private void forceDirectory() throws IOException {
        if (!directoriesCanBeOpened()) {
            return;
        }
        try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            directoryChannel.force(true);
        }
    }

    private static boolean directoriesCanBeOpened() {
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
