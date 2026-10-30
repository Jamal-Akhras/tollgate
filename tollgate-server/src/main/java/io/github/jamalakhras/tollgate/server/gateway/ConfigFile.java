package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.ConfigReader;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.InvalidConfigException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    static GatewayConfig read(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("No Tollgate configuration at " + path.toAbsolutePath()
                    + "; set tollgate.config or TOLLGATE_CONFIG to its location");
        }
        try {
            String yaml = Files.readString(path);
            Map<String, Object> document =
                    yaml.isBlank() ? Map.of() : YAML.readValue(yaml, new TypeReference<Map<String, Object>>() {});
            return ConfigReader.read(document);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        } catch (JacksonException e) {
            throw new IllegalStateException(path + " is not valid YAML: " + e.getOriginalMessage(), e);
        } catch (InvalidConfigException e) {
            throw new IllegalStateException(path + ": " + e.getMessage(), e);
        }
    }
}
