package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StreamRewriterTest {

    private static final String CHUNK =
            "data: {\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}";
    private static final String USAGE_ONLY =
            "data: {\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}";

    private static String text(Optional<byte[]> relayed) {
        return new String(relayed.orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void namesYourModelInEveryChunk() {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);

        assertThat(text(rewriter.rewrite(List.of(CHUNK))))
                .isEqualTo(
                        "data: {\"id\":\"c\",\"model\":\"qwen3\",\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n");
        assertThat(text(rewriter.rewrite(List.of("data:{\"choices\":[]}")))).isEqualTo("data: {\"choices\":[]}\n\n");
    }

    @Test
    void passesOtherLinesThrough() {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);

        assertThat(text(rewriter.rewrite(List.of("data: [DONE]")))).isEqualTo("data: [DONE]\n\n");
        assertThat(text(rewriter.rewrite(List.of(": keep-alive")))).isEqualTo(": keep-alive\n\n");
        assertThat(text(rewriter.rewrite(List.of("event: ping", "data: {\"type\":\"ping\"}"))))
                .isEqualTo("event: ping\ndata: {\"type\":\"ping\"}\n\n");
    }

    @Test
    void readsUsageAndHidesTheUsageChunkFromClientsThatDidNotAskForIt() {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);

        assertThat(rewriter.usage()).isEmpty();
        assertThat(rewriter.rewrite(List.of(USAGE_ONLY))).isEmpty();
        assertThat(rewriter.usage()).contains(new Usage(9, 4, 0, 0));
    }

    @Test
    void relaysTheUsageChunkToClientsThatAskedForIt() {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", true);

        assertThat(text(rewriter.rewrite(List.of(USAGE_ONLY))))
                .contains("\"usage\"")
                .contains("\"model\":\"qwen3\"");
        assertThat(rewriter.usage()).contains(new Usage(9, 4, 0, 0));
    }

    @Test
    void keepsAChunkThatCarriesBothAnAnswerAndUsage() {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);
        String both = "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}";

        assertThat(rewriter.rewrite(List.of(both))).isPresent();
        assertThat(rewriter.usage()).contains(new Usage(1, 2, 0, 0));
        assertThat(rewriter.rewrite(List.of("data: {\"usage\":{\"prompt_tokens\":-1}}")))
                .isPresent();
        assertThat(rewriter.usage()).contains(new Usage(1, 2, 0, 0));
    }

    @Test
    void namesYourModelInAnthropicsMessageStartAndAddsUpItsUsage() {
        var rewriter = new StreamRewriter(WireFormat.ANTHROPIC, "claude", false);

        assertThat(text(rewriter.rewrite(List.of(
                        "event: message_start",
                        "data: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-4-5\",\"usage\":"
                                + "{\"input_tokens\":25,\"output_tokens\":1,\"cache_read_input_tokens\":10}}}"))))
                .isEqualTo("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude\","
                        + "\"usage\":{\"input_tokens\":25,\"output_tokens\":1,\"cache_read_input_tokens\":10}}}\n\n");
        assertThat(rewriter.usage()).contains(new Usage(25, 1, 10, 0));

        rewriter.rewrite(List.of(
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":15,\"cache_creation_input_tokens\":4,"
                        + "\"server_tool_use\":{\"web_search_requests\":2}}}"));
        rewriter.rewrite(List.of("event: message_delta", "data: {\"type\":\"message_delta\",\"usage\":{}}"));

        assertThat(rewriter.usage()).contains(new Usage(25, 15, 10, 4));
        assertThat(rewriter.webSearches()).isEqualTo(2);
    }

    @Test
    void reportsNoAnthropicUsageUntilBothCountsArrive() {
        var rewriter = new StreamRewriter(WireFormat.ANTHROPIC, "claude", false);

        rewriter.rewrite(List.of("data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":15}}"));
        assertThat(rewriter.usage()).isEmpty();
        rewriter.rewrite(List.of("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":-3}}}"));
        assertThat(rewriter.usage()).isEmpty();
        rewriter.rewrite(List.of("data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hi\"}}"));
        assertThat(rewriter.webSearches()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"data: not json", "data: [1,2]", "data: {\"error\":{\"message\":\"overloaded\"}}"})
    void stopsAtAnEventItCannotRelay(String line) {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);

        assertThatThrownBy(() -> rewriter.rewrite(List.of(line))).isInstanceOf(InvalidProviderResponseException.class);
    }
}
