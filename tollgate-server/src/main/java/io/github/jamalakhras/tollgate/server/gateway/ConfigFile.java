package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.ConfigReader;
import io.github.jamalakhras.tollgate.core.config.InvalidConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

final class ConfigFile {

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private ConfigFile() {}

    static LoadedConfig read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("No Tollgate configuration at " + path.toAbsolutePath()
                    + "; set tollgate.config or TOLLGATE_CONFIG to its location");
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            String yaml = new String(bytes, StandardCharsets.UTF_8);
            Map<String, Object> document =
                    yaml.isBlank() ? Map.of() : YAML.readValue(yaml, new TypeReference<Map<String, Object>>() {});
            return new LoadedConfig(path, ConfigReader.read(document), sha256(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        } catch (JacksonException e) {
            throw new IllegalStateException(path + " is not valid YAML: " + e.getOriginalMessage(), e);
        } catch (InvalidConfigException e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
