package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.ConfigFile;
import io.github.jamalakhras.tollgate.gateway.ConfigOperation;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.gateway.GatewayProperties;
import io.github.jamalakhras.tollgate.gateway.StartupException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ConfigEditor {

    private static final Logger log = LoggerFactory.getLogger(ConfigEditor.class);

    private final Gateway gateway;
    private final Path config;
    private final boolean on;

    ConfigEditor(Gateway gateway, GatewayProperties properties, AdminProperties admin) {
        this.gateway = gateway;
        this.config = properties.config();
        this.on = admin.edits().equals("file");
        if (on) {
            requireWritable(config);
            gateway.keepVersion();
            log.info(
                    "The admin API may rewrite {}. Versions are kept in {}",
                    config,
                    config.toAbsolutePath().resolveSibling("config-history"));
        }
    }

    static void requireWritable(Path config) {
        if (!Files.isWritable(config)) {
            throw notWritable(config, "the file");
        }
        try {
            Files.delete(Files.createTempFile(config.toAbsolutePath().getParent(), "tollgate-", ".tmp"));
        } catch (IOException | RuntimeException e) {
            throw notWritable(config, "the folder");
        }
    }

    private static StartupException notWritable(Path config, String what) {
        return new StartupException("TOLLGATE_ADMIN_EDITS=file lets the admin API rewrite " + config + ", and " + what
                + " is not writable. Make the config's folder writable, or remove TOLLGATE_ADMIN_EDITS");
    }

    boolean on() {
        return on;
    }

    boolean rewritten() {
        return Files.exists(ConfigFile.original(config));
    }

    void reloaded() {
        if (on) {
            gateway.keepVersion();
        }
    }

    Gateway.Reload apply(AdminCaller caller, @Nullable String ifMatch, List<ConfigOperation> operations) {
        if (!on) {
            throw new EditsOff();
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new NoVersion();
        }
        String version = unquoted(ifMatch);
        Gateway.Reload reload = gateway.edit(version, operations);
        if (reload.changed()) {
            ManagedWrite write = new ManagedWrite(
                    UUID.randomUUID().toString(),
                    caller,
                    version,
                    reload.version(),
                    operations.stream()
                            .map(operation -> new Operation(operation.name(), operation.path()))
                            .toList());
            log.info(write.line());
        }
        return reload;
    }

    static String unquoted(String ifMatch) {
        String version = ifMatch.strip();
        if (version.length() >= 2 && version.startsWith("\"") && version.endsWith("\"")) {
            return version.substring(1, version.length() - 1);
        }
        return version;
    }

    record Operation(String name, String path) {}

    record ManagedWrite(String id, AdminCaller caller, String before, String after, List<Operation> operations) {

        String line() {
            return "Config change " + id + " by " + caller + ": version " + after + " replaces " + before + "; "
                    + operations.stream()
                            .map(operation -> operation.name() + " " + operation.path())
                            .collect(Collectors.joining(", "));
        }
    }

    static final class EditsOff extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    static final class NoVersion extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
