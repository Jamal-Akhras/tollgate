package io.github.jamalakhras.tollgate.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.server.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.server.audit.JsonLinesAuditSink;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
