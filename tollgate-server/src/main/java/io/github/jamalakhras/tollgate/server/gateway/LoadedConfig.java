package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import java.nio.file.Path;

public record LoadedConfig(Path path, GatewayConfig config, String version) {

    public Path resolve(String relativePath) {
        return path.toAbsolutePath().resolveSibling(relativePath).normalize();
    }
}
