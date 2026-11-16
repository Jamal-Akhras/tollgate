package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigEditTest {

    private static final ApiKey KEY = ApiKey.generate(new SecureRandom());

    private final HttpClient http = ProviderClient.newHttpClient();

    @TempDir
    private Path directory;

    private Path config;
    private byte[] handWritten;

    @AfterEach
    void stop() {
        http.close();
    }

    @BeforeEach
    void write() throws IOException {
        config = Files.writeString(directory.resolve("tollgate.yaml"), """
                # written by hand
                providers:
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1" }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:8b"
                    price: free
                    maxOutputTokens: { default: 1024, cap: 4096 }
                tenants:
                  support-bot: { models: [qwen3] }
                keys:
                  - { id: %s, tenant: support-bot, sha256: %s }
                audit: { mode: off }
                """.formatted(KEY.id(), KEY.sha256()));
        handWritten = Files.readAllBytes(config);
    }

    private Gateway start() {
        return GatewayConfiguration.start(
                ConfigFile.read(config),
                name -> null,
                new ProviderClient(http, 1_024 * 1_024),
                Clock.systemUTC(),
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Tracer.NOOP);
    }

    @SuppressWarnings("unchecked")
    private static ConfigOperation showCosts(boolean value) {
        return new ConfigOperation("show-costs", "tenants.support-bot.showCosts", document -> {
            Map<String, Object> tenants = (Map<String, Object>) Objects.requireNonNull(document.get("tenants"));
            Map<String, Object> tenant = (Map<String, Object>) Objects.requireNonNull(tenants.get("support-bot"));
            tenant.put("showCosts", value);
            return document;
        });
    }

    @SuppressWarnings("unchecked")
    private static ConfigOperation keyForGhost() {
        return new ConfigOperation("add-key", "keys", document -> {
            List<Object> keys = (List<Object>) Objects.requireNonNull(document.get("keys"));
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("id", "ghost-key");
            key.put("tenant", "ghost");
            key.put("sha256", "b".repeat(64));
            keys.add(key);
            return document;
        });
    }

    private static ConfigOperation audited() {
        AuditKeys.NewKeys keys = AuditKeys.generate(new SecureRandom());
        return new ConfigOperation("audit", "audit", document -> {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("mode", "sync");
            audit.put("directory", "audit");
            audit.put("publicKey", keys.publicKey());
            audit.put("signingKey", "${env:TOLLGATE_AUDIT_SIGNING_KEY}");
            audit.put("hmacKey", "${env:TOLLGATE_AUDIT_HMAC_KEY}");
            document.put("audit", audit);
            return document;
        });
    }

    private static boolean showsCosts(Gateway gateway) {
        return Objects.requireNonNull(gateway.config().tenants().get("support-bot"))
                .showCosts();
    }

    private List<String> leftovers() throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private void assertUntouched(Gateway gateway, String version) throws IOException {
        assertThat(Files.readAllBytes(config)).isEqualTo(handWritten);
        assertThat(gateway.configVersion()).isEqualTo(version);
        assertThat(showsCosts(gateway)).isFalse();
    }

    @Test
    void aChangeIsInTheFileAndInTheRunningConfig() throws Exception {
        try (Gateway gateway = start()) {
            String before = gateway.configVersion();

            Gateway.Reload reload = gateway.edit(before, List.of(showCosts(true)));

            assertThat(reload.changed()).isTrue();
            assertThat(reload.version()).isEqualTo(ConfigFile.read(config).version());
            assertThat(gateway.configVersion()).isEqualTo(reload.version()).isNotEqualTo(before);
            assertThat(showsCosts(gateway)).isTrue();
            assertThat(Files.readString(config)).startsWith(ConfigFile.NOTE).doesNotContain("written by hand");
        }
    }

    @Test
    void theFirstWriteKeepsTheHandWrittenFileAndASecondLeavesItAlone() throws Exception {
        try (Gateway gateway = start()) {
            gateway.edit(gateway.configVersion(), List.of(showCosts(true)));
            assertThat(Files.readAllBytes(ConfigFile.original(config))).isEqualTo(handWritten);

            gateway.edit(gateway.configVersion(), List.of(showCosts(false)));
            assertThat(Files.readAllBytes(ConfigFile.original(config))).isEqualTo(handWritten);
        }
    }

    @Test
    void historyHoldsEveryVersionThatRanWithTheBytesThatRan() throws Exception {
        try (Gateway gateway = start()) {
            String first = gateway.configVersion();
            gateway.edit(first, List.of(showCosts(true)));
            String second = gateway.configVersion();
            byte[] secondBytes = Files.readAllBytes(config);
            Path history = directory.resolve("config-history");

            assertThat(Files.readAllBytes(history.resolve(first + ".yaml"))).isEqualTo(handWritten);
            assertThat(Files.readAllBytes(history.resolve(second + ".yaml"))).isEqualTo(secondBytes);
            assertThat(history.toFile().list()).hasSize(2);

            gateway.edit(second, List.of(showCosts(false)));

            assertThat(history.toFile().list()).hasSize(3);
            assertThat(Files.readAllBytes(history.resolve(gateway.configVersion() + ".yaml")))
                    .isEqualTo(Files.readAllBytes(config));
        }
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws Exception {
        try (Gateway gateway = start()) {
            gateway.edit(gateway.configVersion(), List.of(showCosts(true)));

            assertThat(leftovers()).doesNotContain("tollgate.yaml.tmp");
        }
    }

    @Test
    void aWrongVersionIsRefusedAndNothingChanges() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();

            assertThatThrownBy(() -> gateway.edit("a".repeat(64), List.of(showCosts(true))))
                    .isInstanceOfSatisfying(EditRejected.class, e -> {
                        assertThat(e.code()).isEqualTo(EditRejected.CONFIG_CHANGED);
                        assertThat(e.getMessage()).contains("aaaaaaaaaaaa…").contains("Read it again");
                    });

            assertUntouched(gateway, version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void aFileChangedByHandAndNotReloadedIsRefusedAndNothingChanges() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();
            Files.writeString(config, Files.readString(config) + "# touched\n");
            byte[] touched = Files.readAllBytes(config);

            assertThatThrownBy(() -> gateway.edit(version, List.of(showCosts(true))))
                    .isInstanceOfSatisfying(EditRejected.class, e -> {
                        assertThat(e.code()).isEqualTo(EditRejected.CONFIG_CHANGED);
                        assertThat(e.getMessage()).contains("was changed and has not been reloaded");
                    });

            assertThat(Files.readAllBytes(config)).isEqualTo(touched);
            assertThat(gateway.configVersion()).isEqualTo(version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void aChangeThatMakesTheConfigInvalidWritesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();

            assertThatThrownBy(() -> gateway.edit(version, List.of(keyForGhost())))
                    .isInstanceOfSatisfying(
                            ReloadRejected.class, e -> assertThat(e.code()).isEqualTo(ReloadRejected.INVALID_CONFIG));

            assertUntouched(gateway, version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void aChangeToTheAuditSettingsNeedsARestartAndWritesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();

            assertThatThrownBy(() -> gateway.edit(version, List.of(audited())))
                    .isInstanceOfSatisfying(
                            ReloadRejected.class, e -> assertThat(e.code()).isEqualTo(ReloadRejected.RESTART_REQUIRED));

            assertUntouched(gateway, version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void aChangeThatChangesNothingWritesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();

            Gateway.Reload reload =
                    gateway.edit(version, List.of(new ConfigOperation("nothing", "tenants", document -> document)));

            assertThat(reload).isEqualTo(new Gateway.Reload(version, false));
            assertUntouched(gateway, version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void anExceptionFromAnOperationReachesTheCallerAndWritesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();
            ConfigOperation failing = new ConfigOperation("fails", "tenants", document -> {
                throw new IllegalArgumentException("No such tenant");
            });

            assertThatThrownBy(() -> gateway.edit(version, List.of(showCosts(true), failing)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No such tenant");

            assertUntouched(gateway, version);
            assertThat(leftovers()).containsExactly("tollgate.yaml");
        }
    }

    @Test
    void operationsRunInOrderAndAreWrittenOnce() throws Exception {
        try (Gateway gateway = start()) {
            gateway.edit(gateway.configVersion(), List.of(showCosts(false), showCosts(true)));

            assertThat(showsCosts(gateway)).isTrue();
            assertThat(directory.resolve("config-history").toFile().list()).hasSize(2);
        }
    }

    @Test
    void aHistoryFolderThatCannotBeMadeFailsTheWriteAndChangesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();
            Files.writeString(directory.resolve("config-history"), "in the way");

            assertThatThrownBy(() -> gateway.edit(version, List.of(showCosts(true))))
                    .isInstanceOfSatisfying(
                            EditRejected.class, e -> assertThat(e.code()).isEqualTo(EditRejected.WRITE_FAILED));

            assertUntouched(gateway, version);
            assertThat(leftovers()).doesNotContain("tollgate.yaml.tmp");
        }
    }

    @Test
    void aTemporaryFileThatCannotBeWrittenFailsTheWriteAndChangesNothing() throws Exception {
        try (Gateway gateway = start()) {
            String version = gateway.configVersion();
            Files.createDirectory(directory.resolve("tollgate.yaml.tmp"));
            Files.writeString(directory.resolve("tollgate.yaml.tmp").resolve("held"), "x");

            assertThatThrownBy(() -> gateway.edit(version, List.of(showCosts(true))))
                    .isInstanceOfSatisfying(
                            EditRejected.class, e -> assertThat(e.code()).isEqualTo(EditRejected.WRITE_FAILED));

            assertUntouched(gateway, version);
        }
    }

    @Test
    void twoEditsWithTheSameVersionLetOneThrough() throws Exception {
        try (Gateway gateway = start();
                ExecutorService threads = Executors.newFixedThreadPool(2)) {
            String version = gateway.configVersion();
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Gateway.Reload>> edits = new ArrayList<>();
            for (boolean value : new boolean[] {true, false}) {
                edits.add(threads.submit(() -> {
                    go.await();
                    return gateway.edit(version, List.of(value ? showCosts(true) : currency()));
                }));
            }
            go.countDown();

            int succeeded = 0;
            int refused = 0;
            for (Future<Gateway.Reload> edit : edits) {
                try {
                    edit.get();
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(EditRejected.class);
                    assertThat(((EditRejected) Objects.requireNonNull(e.getCause())).code())
                            .isEqualTo(EditRejected.CONFIG_CHANGED);
                    refused++;
                }
            }

            assertThat(succeeded).isEqualTo(1);
            assertThat(refused).isEqualTo(1);
        }
    }

    private static ConfigOperation currency() {
        return new ConfigOperation("currency", "currency", document -> {
            document.put("currency", "USD");
            return document;
        });
    }

    @Test
    void keepVersionWritesTheRunningVersionOnceAndNothingAfterAHandEdit() throws Exception {
        try (Gateway gateway = start()) {
            FileConfigStore store = new FileConfigStore(config);

            gateway.keepVersion();
            gateway.keepVersion();
            assertThat(store.versions()).containsExactly(gateway.configVersion());

            Files.writeString(config, Files.readString(config) + "# touched\n");
            gateway.keepVersion();

            assertThat(store.versions()).containsExactly(gateway.configVersion());
        }
    }

    @Test
    void theFileStoreWritesNothingWhenTheFileIsNotTheExpectedVersion() throws Exception {
        start().close();
        FileConfigStore store = new FileConfigStore(config);

        boolean replaced = store.replace("c".repeat(64), "d".repeat(64), new byte[] {1});

        assertThat(replaced).isFalse();
        assertThat(Files.readAllBytes(config)).isEqualTo(handWritten);
        assertThat(leftovers()).containsExactly("tollgate.yaml");
    }

    @Test
    void theFileStoreReturnsAKeptVersionAndNothingElse() throws Exception {
        try (Gateway gateway = start()) {
            FileConfigStore store = new FileConfigStore(config);
            gateway.keepVersion();

            assertThat(store.version(gateway.configVersion()).orElseThrow()).isEqualTo(handWritten);
            assertThat(store.version("e".repeat(64))).isEmpty();
            assertThat(store.version("../x")).isEmpty();
            assertThat(store.version("..\\tollgate.yaml")).isEmpty();
        }
    }

    @Test
    void theFileStoreListsKeptVersionsNewestFirst() throws Exception {
        start().close();
        FileConfigStore store = new FileConfigStore(config);
        assertThat(store.versions()).isEmpty();
        String old = "1".repeat(64);
        String recent = "2".repeat(64);
        Path history = directory.resolve("config-history");
        Files.createDirectory(history);
        Files.write(history.resolve(old + ".yaml"), new byte[] {1});
        Files.write(history.resolve(recent + ".yaml"), new byte[] {2});
        Files.write(history.resolve("notes.yaml"), new byte[] {3});
        Files.setLastModifiedTime(history.resolve(old + ".yaml"), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(
                history.resolve(recent + ".yaml"), FileTime.from(Instant.parse("2026-02-01T00:00:00Z")));

        assertThat(store.versions()).containsExactly(recent, old);
        assertThat(store.version(old).orElseThrow()).isEqualTo(new byte[] {1});
    }
}
