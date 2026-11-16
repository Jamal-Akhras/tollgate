package io.github.jamalakhras.tollgate.gateway;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

final class FileConfigStore implements ConfigStore {

    private static final String HISTORY = "config-history";
    private static final String EXTENSION = ".yaml";
    private static final Pattern VERSION = Pattern.compile("[0-9a-f]{64}");

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

    @Override
    public Optional<byte[]> version(String version) throws IOException {
        if (!VERSION.matcher(version).matches()) {
            return Optional.empty();
        }
        Path file = history(path).resolve(version + EXTENSION);
        return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
    }

    @Override
    public List<String> versions() throws IOException {
        Path folder = history(path);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        Map<String, FileTime> kept = new HashMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String version = name.substring(0, name.length() - EXTENSION.length());
                if (VERSION.matcher(version).matches() && Files.isRegularFile(file)) {
                    kept.put(version, Files.getLastModifiedTime(file));
                }
            }
        }
        return kept.entrySet().stream()
                .sorted(Map.Entry.<String, FileTime>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean writeIfAbsent(Path file, byte[] bytes) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        Files.write(file, bytes);
        return true;
    }
}
