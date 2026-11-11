package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileTest {

    @TempDir
    private Path directory;

    private Path write(String yaml) throws IOException {
        return Files.writeString(directory.resolve("tollgate.yaml"), yaml);
    }

    @Test
    void readsAConfigFile() throws IOException {
        Path file = write("""
                providers:
                  ollama: { format: openai, baseUrl: "http://gpu-box:11434/v1" }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 0.10, output: 0.40, cacheRead: 0.10, cacheWrite: 0.10 }
                tenants:
                  support-bot: { models: [qwen3] }
                audit: { mode: off }
                """);

        var route = ConfigFile.read(file).config().route("support-bot", "qwen3").orElseThrow();
        assertThat(route.definition().price().rates().output().perMillionTokens())
                .hasToString("0.4 USD");
    }

    @Test
    void asksAnEmptyFileForTheAuditSettings() throws IOException {
        Path file = write("");

        assertThatThrownBy(() -> ConfigFile.read(file)).hasMessage(file + ": audit is required");
    }

    @Test
    void readsOffAsAWordRatherThanFalse() throws IOException {
        assertThat(ConfigFile.read(write("audit: { mode: off }")).config().audit())
                .isEqualTo(AuditSettings.DISABLED);
    }

    @Test
    void explainsHowToPointAtAMissingFile() {
        assertThatThrownBy(() -> ConfigFile.read(directory.resolve("missing.yaml")))
                .hasMessageStartingWith("No Tollgate configuration at")
                .hasMessageEndingWith("set tollgate.config or TOLLGATE_CONFIG to its location");
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = write("""
                tenants:
                  support-bot: { models: [] }
                  support-bot: { models: [] }
                """);

        assertThatThrownBy(() -> ConfigFile.read(file))
                .hasMessageContaining("is not valid YAML")
                .hasMessageContaining("support-bot");
    }

    @Test
    void namesTheFileAndTheSettingThatIsWrong() throws IOException {
        Path file = write("tenants:\n  support-bot: { modles: [] }\n");

        assertThatThrownBy(() -> ConfigFile.read(file))
                .hasMessage(file + ": tenants.support-bot.modles is not a recognised setting");
    }
}
