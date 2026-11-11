package io.github.jamalakhras.tollgate.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.Model;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.PriceCatalog;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.gateway.ConfigFile;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.JsonLinesAuditSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandsTest {

    private static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());

    @TempDir
    private Path trail;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Commands commands = new Commands(
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            new SecureRandom());

    @Test
    void createsAKeyAndTheConfigEntryThatMatchesIt() {
        int status = commands.run(List.of("keys", "create", "--tenant", "support-bot"));

        assertThat(status).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        Matcher key = Pattern.compile("tg_\\w+").matcher(printed);
        assertThat(key.find()).isTrue();
        ApiKey created = ApiKey.parse(key.group()).orElseThrow();
        assertThat(printed)
                .contains("- { id: " + created.id() + ", tenant: support-bot, sha256: " + created.sha256() + " }");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void rejectsTenantNamesThatCannotAppearInConfig() {
        int status = commands.run(List.of("keys", "create", "--tenant", "x, sha256: forged"));

        assertThat(status).isEqualTo(Commands.USAGE_ERROR);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("tenant 'x, sha256: forged' must start with");
    }

    @Test
    void createsAuditKeysThatWorkTogether() {
        int status = commands.run(List.of("audit", "keys", "create"));

        assertThat(status).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        String signingKey = valueAfter(printed, "TOLLGATE_AUDIT_SIGNING_KEY=");
        String hmacKey = valueAfter(printed, "TOLLGATE_AUDIT_HMAC_KEY=");
        String publicKey = valueAfter(printed, "publicKey: ");
        assertThat(AuditKeys.of(publicKey, signingKey, hmacKey).hmac(new byte[] {1}))
                .hasSize(64);
        assertThat(printed).contains("signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}");
    }

    @Test
    void initCreatesAConfigThatLoadsAndKeepsItsSecretsInAPrivateFile() throws IOException {
        Path directory = trail.resolve("new");

        int status = commands.run(List.of("init", directory.toString()));

        assertThat(status).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        Matcher shown = Pattern.compile("tg_\\w+").matcher(printed);
        assertThat(shown.find()).isTrue();
        ApiKey key = ApiKey.parse(shown.group()).orElseThrow();
        GatewayConfig config = ConfigFile.read(directory.resolve(Init.CONFIG)).config();
        assertThat(config.keys())
                .singleElement()
                .satisfies(entry -> assertThat(entry.sha256()).isEqualTo(key.sha256()));
        Policy policy = Objects.requireNonNull(config.policies().get(GatewayConfig.DEFAULT_POLICY));
        for (String dataClass : config.dataClasses()) {
            assertThat(policy.refuses(dataClass)).containsExactly(Detector.SECRET);
            assertThat(policy.redacts(dataClass)).containsExactly(Detector.CARD);
        }

        String secrets = Files.readString(directory.resolve(Init.SECRETS));
        assertThat(printed).doesNotContain(valueAfter(secrets, "TOLLGATE_AUDIT_SIGNING_KEY="));
        assertThat(valueAfter(secrets, "TOLLGATE_ADMIN_TOKEN=")).hasSize(64);
        assertThat(config.audit())
                .isInstanceOfSatisfying(
                        AuditSettings.Enabled.class,
                        audit -> assertThat(AuditKeys.of(
                                                audit.publicKey(),
                                                valueAfter(secrets, "TOLLGATE_AUDIT_SIGNING_KEY="),
                                                valueAfter(secrets, "TOLLGATE_AUDIT_HMAC_KEY="))
                                        .hmac(new byte[] {1}))
                                .hasSize(64));
        if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve(Init.SECRETS))))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void initNeverOverwritesAConfigOrItsSecrets() throws IOException {
        Files.writeString(trail.resolve(Init.SECRETS), "TOLLGATE_ADMIN_TOKEN=keep-me\n");

        int status = commands.run(List.of("init", trail.toString()));

        assertThat(status).isEqualTo(Commands.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("never overwrites");
        assertThat(trail.resolve(Init.CONFIG)).doesNotExist();
        assertThat(trail.resolve(Init.SECRETS)).hasContent("TOLLGATE_ADMIN_TOKEN=keep-me");
    }

    @Test
    void verifiesAnIntactTrail() throws IOException {
        writeTrail();

        int status = commands.run(List.of("audit", "verify", "--public-key", AUDIT_KEYS.publicKey(), trail.toString()));

        assertThat(status).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Checked 2 records in 1 sealed batch.")
                .contains("The last batch is 1, sealed at")
                .endsWith("The audit trail is intact." + System.lineSeparator());
    }

    @Test
    void failsATamperedTrailAndSaysWhere() throws IOException {
        writeTrail();
        Path file = AuditDirectory.files(trail).getFirst();
        Files.writeString(file, Files.readString(file).replace("support-bot", "billing"));

        int status = commands.run(List.of("audit", "verify", "--public-key", AUDIT_KEYS.publicKey(), trail.toString()));

        assertThat(status).isEqualTo(Commands.FAILED);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Failed: tollgate-audit-00000001.jsonl:3: Batch 1 does not match the records it seals")
                .contains("The audit trail FAILED verification with 1 problem.");
    }

    @Test
    void explainsAMissingTrailOrABadKey() {
        assertThat(commands.run(List.of(
                        "audit",
                        "verify",
                        "--public-key",
                        AUDIT_KEYS.publicKey(),
                        trail.resolve("x").toString())))
                .isEqualTo(Commands.FAILED);
        assertThat(commands.run(List.of("audit", "verify", "--public-key", "%%%", trail.toString())))
                .isEqualTo(Commands.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("No audit trail at")
                .contains("publicKey is not valid base64");
    }

    private Path usageConfig(String audit) throws IOException {
        return Files.writeString(trail.resolve("tollgate.yaml"), "audit: " + audit + System.lineSeparator());
    }

    @Test
    void sumsTheAuditTrailFromTheCommandLine() throws Exception {
        writeTrail();
        Path config = usageConfig("{ mode: sync, directory: ., publicKey: " + AUDIT_KEYS.publicKey()
                + ", signingKey: \"${env:SIGNING}\", hmacKey: \"${env:HMAC}\" }");

        int status = commands.run(
                List.of("usage", config.toString(), "--from", "1970-01-01", "--to", "1970-01-02", "--by", "tenant"));

        assertThat(status).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("Usage from 1970-01-01 up to 1970-01-02, by tenant:")
                .containsPattern("support-bot +0 +2 +0 +0 +0 +0 +0 +-");
    }

    @Test
    void explainsWhyItCannotSumTheTrail() throws Exception {
        assertThat(commands.run(List.of("usage", usageConfig("{ mode: off }").toString())))
                .isEqualTo(Commands.FAILED);
        assertThat(commands.run(List.of("usage", "tollgate.yaml", "--by"))).isEqualTo(Commands.USAGE_ERROR);
        assertThat(commands.run(List.of("usage", "tollgate.yaml", "--within", "1d")))
                .isEqualTo(Commands.USAGE_ERROR);
        Path config = usageConfig("{ mode: sync, directory: missing, publicKey: " + AUDIT_KEYS.publicKey()
                + ", signingKey: \"${env:SIGNING}\", hmacKey: \"${env:HMAC}\" }");
        assertThat(commands.run(List.of("usage", config.toString(), "--by", "week")))
                .isEqualTo(Commands.USAGE_ERROR);
        assertThat(commands.run(List.of("usage", config.toString()))).isEqualTo(Commands.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("audit.mode is off")
                .contains("by must be one of tenant, model, key, day, was week")
                .contains("Could not read the audit trail");
    }

    private void writeTrail() throws IOException {
        AuditKeys keys = AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());
        Decision denial = Decision.deny(Refusal.POLICY_DENIED, "No");
        try (AuditLog log = AuditLog.start(JsonLinesAuditSink.open(trail), keys, Clock.systemUTC(), true)) {
            for (String id : List.of("a", "b")) {
                log.append(new Intent(
                        id,
                        Instant.EPOCH,
                        "support-bot",
                        "k7Qm2xPa4bZr",
                        WireFormat.OPENAI,
                        "qwen3",
                        "ollama",
                        "qwen3:32b",
                        null,
                        "0".repeat(64),
                        "0".repeat(64),
                        List.of(),
                        denial,
                        null));
            }
        }
    }

    private static String valueAfter(String text, String prefix) {
        Matcher value = Pattern.compile(Pattern.quote(prefix) + "(\\S+)").matcher(text);
        assertThat(value.find()).isTrue();
        return value.group(1);
    }

    @Test
    void showsAModelsPriceInTheCatalog() {
        int status = commands.run(List.of("prices", "show", "alibaba", "qwen-plus"));

        assertThat(status).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("qwen-plus on alibaba, in USD per million tokens, from models.dev ")
                .contains("    price:", "      reasoning: ", "      longContext:", "        - { fromTokens: ");
    }

    @Test
    void saysWhenTheCatalogHasNoSuchModel() {
        assertThat(commands.run(List.of("prices", "show", "nowhere", "qwen-plus")))
                .isEqualTo(Commands.FAILED);
        assertThat(commands.run(List.of("prices", "show", "alibaba", "qwen-next")))
                .isEqualTo(Commands.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8).lines())
                .containsExactly(
                        "The price catalog has no provider nowhere",
                        "The alibaba price catalog has no model qwen-next");
    }

    @Test
    void pinsTheCatalogPricesAConfigUsesSoTheyCanBePastedBackIn() throws IOException {
        String providers = """
                providers:
                  dashscope: { format: openai, baseUrl: "https://dashscope-intl.aliyuncs.com/compatible-mode/v1" }
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1" }
                audit: { mode: off }
                """;
        Path config = Files.writeString(trail.resolve("tollgate.yaml"), providers + """
                models:
                  qwen: { provider: dashscope, upstream: qwen-plus }
                  local: { provider: ollama, upstream: "qwen3:8b", price: free }
                """);

        int status = commands.run(List.of("prices", "pin", config.toString()));

        assertThat(status).isZero();
        String printed = out.toString(StandardCharsets.UTF_8);
        assertThat(printed).contains("      qwen:").doesNotContain("local:");
        String pinned = printed.substring(printed.indexOf("    models:"))
                .stripTrailing()
                .stripIndent()
                .replace("  qwen:\n", "  qwen:\n    provider: dashscope\n    upstream: qwen-plus\n");
        Model model = Objects.requireNonNull(
                ConfigFile.read(Files.writeString(trail.resolve("pinned.yaml"), providers + pinned))
                        .config()
                        .models()
                        .get("qwen"));
        assertThat(model.priceCatalog()).isNull();
        assertThat(model.price())
                .isEqualTo(PriceCatalog.bundled().price("alibaba", "qwen-plus").orElseThrow());
    }

    @Test
    void pinsNothingWhenEveryPriceIsWritten() throws IOException {
        Path config = Files.writeString(trail.resolve("tollgate.yaml"), """
                providers: { ollama: { format: openai, baseUrl: "http://localhost:11434/v1" } }
                models: { local: { provider: ollama, upstream: "qwen3:8b", price: free } }
                audit: { mode: off }
                """);

        assertThat(commands.run(List.of("prices", "pin", config.toString()))).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).lines())
                .containsExactly("No model in " + config + " takes its price from the catalog.");
        assertThat(commands.run(
                        List.of("prices", "pin", trail.resolve("missing.yaml").toString())))
                .isEqualTo(Commands.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("No Tollgate configuration at");
    }

    @Test
    void printsUsageForAnythingElse() {
        assertThat(commands.run(List.of("keys", "delete", "--tenant", "support-bot")))
                .isEqualTo(Commands.USAGE_ERROR);
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("Usage:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"keys", "audit"})
    void treatsAWordAsACommand(String first) {
        assertThat(Commands.isCommand(new String[] {first})).isTrue();
    }

    @Test
    void leavesSpringOptionsToTheServer() {
        assertThat(Commands.isCommand(new String[] {"--server.port=9000"})).isFalse();
        assertThat(Commands.isCommand(new String[0])).isFalse();
    }
}
