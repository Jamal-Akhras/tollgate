package io.github.jamalakhras.tollgate.gateway;

import io.github.jamalakhras.tollgate.core.config.ConfigReader;
import io.github.jamalakhras.tollgate.core.config.InvalidConfigException;
import io.github.jamalakhras.tollgate.core.money.PriceCatalog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

public final class ConfigFile {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    static final String NOTE = "# Rewritten by Tollgate's admin API. Comments are not kept. "
            + "The file as it was is beside this one, with .original added to its name.\n";

    private static final YAMLMapper WRITER = YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.INDENT_ARRAYS_WITH_INDICATOR)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private ConfigFile() {}

    public static LoadedConfig read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("No Tollgate configuration at " + path.toAbsolutePath()
                    + "; set tollgate.config or TOLLGATE_CONFIG to its location");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
        return parse(path, bytes);
    }

    static LoadedConfig parse(Path path, byte[] bytes) {
        try {
            PriceCatalog catalog = PriceCatalog.bundled();
            return new LoadedConfig(path, ConfigReader.read(document(bytes), catalog), version(bytes));
        } catch (JacksonException e) {
            throw new IllegalStateException(path + " is not valid YAML: " + e.getOriginalMessage(), e);
        } catch (InvalidConfigException e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
    }

    static Map<String, Object> document(byte[] bytes) {
        String yaml = new String(bytes, StandardCharsets.UTF_8);
        return yaml.isBlank() ? new LinkedHashMap<>() : YAML.readValue(yaml, new TypeReference<>() {});
    }

    static byte[] write(Map<String, Object> document) {
        return (NOTE + WRITER.writeValueAsString(document)).getBytes(StandardCharsets.UTF_8);
    }

    public static Path original(Path config) {
        return config.resolveSibling(config.getFileName() + ".original");
    }

    static String version(byte[] bytes) {
        try {
            PriceCatalog catalog = PriceCatalog.bundled();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((catalog.version() + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
