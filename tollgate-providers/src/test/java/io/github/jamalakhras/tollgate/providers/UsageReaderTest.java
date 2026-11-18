package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsageReaderTest {

    @Test
    void readsTheWebSearchesAnthropicReports() {
        byte[] body = bytes(
                "{\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"server_tool_use\":{\"web_search_requests\":4}}}");

        assertThat(UsageReader.webSearches(WireFormat.ANTHROPIC, body)).isEqualTo(4);
        assertThat(UsageReader.webSearches(WireFormat.OPENAI, body)).isZero();
        assertThat(UsageReader.webSearches(WireFormat.ANTHROPIC, bytes("{}"))).isZero();
        assertThat(UsageReader.webSearches(WireFormat.ANTHROPIC, bytes("not json")))
                .isZero();
        assertThat(UsageReader.webSearches(
                        WireFormat.ANTHROPIC, bytes("{\"usage\":{\"server_tool_use\":{\"web_search_requests\":-1}}}")))
                .isZero();
    }

    @Test
    void readsTheReasoningTokensOpenAiFormatProvidersReport() {
        byte[] body = bytes("""
                {"usage":{"prompt_tokens":53,"completion_tokens":31,
                 "completion_tokens_details":{"reasoning_tokens":20}}}""");

        assertThat(UsageReader.reasoningTokens(WireFormat.OPENAI, body)).isEqualTo(20);
        assertThat(UsageReader.reasoningTokens(WireFormat.ANTHROPIC, body)).isZero();
        assertThat(UsageReader.reasoningTokens(WireFormat.OPENAI, bytes("{}"))).isZero();
        assertThat(UsageReader.reasoningTokens(WireFormat.OPENAI, bytes("not json")))
                .isZero();
        assertThat(UsageReader.reasoningTokens(
                        WireFormat.OPENAI,
                        bytes("{\"usage\":{\"completion_tokens_details\":{\"reasoning_tokens\":-1}}}")))
                .isZero();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Optional<Usage> read(WireFormat format, String body) {
        return UsageReader.read(format, bytes(body));
    }

    @Test
    void separatesCachedTokensFromOpenAiPromptTokens() {
        String body = """
                {"id":"chatcmpl-1","usage":{"prompt_tokens":1200,"completion_tokens":80,
                 "prompt_tokens_details":{"cached_tokens":1000}}}""";

        assertThat(read(WireFormat.OPENAI, body)).contains(new Usage(200, 80, 1_000, 0));
    }

    @Test
    void readsOpenAiUsageWithoutCacheDetails() {
        String body = "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}";

        assertThat(read(WireFormat.OPENAI, body)).contains(new Usage(12, 5, 0, 0));
    }

    @Test
    void readsAnthropicCacheReadsAndWritesSeparately() {
        String body = """
                {"type":"message","usage":{"input_tokens":50,"output_tokens":30,
                 "cache_read_input_tokens":4000,"cache_creation_input_tokens":600}}""";

        assertThat(read(WireFormat.ANTHROPIC, body)).contains(new Usage(50, 30, 4_000, 600));
    }

    @Test
    void readsTheCacheWritesAnthropicKeepsForAnHour() {
        byte[] body = bytes("""
                {"usage":{"input_tokens":5,"output_tokens":3,"cache_creation_input_tokens":900,
                 "cache_creation":{"ephemeral_5m_input_tokens":300,"ephemeral_1h_input_tokens":600}}}""");

        assertThat(UsageReader.cacheWrite1hTokens(WireFormat.ANTHROPIC, body)).isEqualTo(600);
        assertThat(UsageReader.cacheWrite1hTokens(WireFormat.OPENAI, body)).isZero();
        assertThat(UsageReader.cacheWrite1hTokens(WireFormat.ANTHROPIC, bytes("not json")))
                .isZero();
    }

    @Test
    void readsAnthropicUsageWithoutCaching() {
        String body = "{\"usage\":{\"input_tokens\":50,\"output_tokens\":30,\"cache_read_input_tokens\":null}}";

        assertThat(read(WireFormat.ANTHROPIC, body)).contains(new Usage(50, 30, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not json",
                "{}",
                "{\"usage\":{\"prompt_tokens\":12}}",
                "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":5}}",
                "{\"usage\":{\"prompt_tokens\":\"12\",\"completion_tokens\":5}}",
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":13}}}",
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":1.5}}}"
            })
    void reportsNothingForOpenAiUsageItCannotTrust(String body) {
        assertThat(read(WireFormat.OPENAI, body)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"usage\":{\"output_tokens\":30}}",
                "{\"usage\":{\"input_tokens\":50}}",
                "{\"usage\":{\"input_tokens\":50,\"output_tokens\":30,\"cache_read_input_tokens\":-1}}",
                "{\"usage\":{\"input_tokens\":50,\"output_tokens\":30,\"cache_creation_input_tokens\":\"x\"}}"
            })
    void reportsNothingForAnthropicUsageItCannotTrust(String body) {
        assertThat(read(WireFormat.ANTHROPIC, body)).isEmpty();
    }
}
