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

    @ParameterizedTest
    @ValueSource(strings = {"data: not json", "data: [1,2]", "data: {\"error\":{\"message\":\"overloaded\"}}"})
    void stopsAtAnEventItCannotRelay(String line) {
        var rewriter = new StreamRewriter(WireFormat.OPENAI, "qwen3", false);

        assertThatThrownBy(() -> rewriter.rewrite(List.of(line))).isInstanceOf(InvalidProviderResponseException.class);
    }
}
