package io.github.jamalakhras.tollgate.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandsTest {

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
