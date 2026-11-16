package io.github.jamalakhras.tollgate.gateway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class FileConfigStore implements ConfigStore {

    private static final String HISTORY = "config-history";
    private static final String EXTENSION = ".yaml";

    private final Path path;

    FileConfigStore(Path path) {
        this.path = path;
    }

    static Path history(Path config) {
        return config.resolveSibling(HISTORY);
    }

    @Override
    public Head head() throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return new Head(ConfigFile.version(bytes), bytes);
    }

    @Override
    public boolean replace(String expected, String next, byte[] bytes) throws IOException {
        Head head = head();
        if (!head.version().equals(expected)) {
            return false;
        }
        Path original = ConfigFile.original(path);
        if (!Files.exists(original)) {
            Files.copy(path, original);
        }
        Path folder = history(path);
        Files.createDirectories(folder);
        writeIfAbsent(folder.resolve(expected + EXTENSION), head.bytes());
        Path kept = folder.resolve(next + EXTENSION);
        boolean created = writeIfAbsent(kept, bytes);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            if (created) {
                Files.deleteIfExists(kept);
            }
            throw e;
        }
        return true;
    }

    @Override
    public void keep(Head head) throws IOException {
        Path folder = history(path);
        Files.createDirectories(folder);
        writeIfAbsent(folder.resolve(head.version() + EXTENSION), head.bytes());
    }

    private static boolean writeIfAbsent(Path file, byte[] bytes) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }
}
