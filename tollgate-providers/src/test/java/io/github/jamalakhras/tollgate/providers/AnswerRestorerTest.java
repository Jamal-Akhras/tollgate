package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.core.redact.Placeholders;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class AnswerRestorerTest {

    private static final String JANE = "jane@example.com";
    private static final String QUOTED = "\"Jane\"\\ <jane@example.com>";

    private static final Placeholders PLACEHOLDERS = placeholders();

    private static Placeholders placeholders() {
        Redactor redactor = new Redactor(Set.of(), Set.copyOf(Detector.BUILT_IN));
        redactor.redact(JANE + " bob@example.org 4111111111111111");
        return redactor.placeholders();
    }

    private static ProviderResponse response(String json) {
        return new ProviderResponse(200, json.getBytes(StandardCharsets.UTF_8), "application/json", null);
    }

    private static JsonNode restored(String json, WireFormat format) {
        return ChatRequest.JSON.readTree(
                response(json).restored(format, PLACEHOLDERS).body());
    }

    @Test
    void restoresAnOpenAiAnswerAndItsToolCalls() {
        String json = """
                {"id":"c","choices":[{"index":0,"message":{"role":"assistant","content":"Mail [EMAIL_1] or [EMAIL_9]",
                  "reasoning_content":"[EMAIL_2] is cc","refusal":null,"tool_calls":[
                    {"id":"t","type":"function","function":{"name":"send","arguments":"{\\"to\\":\\"[EMAIL_1]\\"}"}},
                    {"id":"u","type":"custom","custom":{"name":"note","input":"card [CARD_1]"}}]}},
                 {"index":1,"message":{"role":"assistant","content":"[CARD_1]"}}],"usage":{"prompt_tokens":1}}
                """;

        JsonNode answer = restored(json, WireFormat.OPENAI);

        JsonNode message = answer.path("choices").path(0).path("message");
        assertThat(message.path("content").stringValue()).isEqualTo("Mail jane@example.com or [EMAIL_9]");
        assertThat(message.path("reasoning_content").stringValue()).isEqualTo("bob@example.org is cc");
        assertThat(message.path("refusal").isNull()).isTrue();
        assertThat(message.path("role").stringValue()).isEqualTo("assistant");
        assertThat(message.path("tool_calls")
                        .path(0)
                        .path("function")
                        .path("arguments")
                        .stringValue())
                .isEqualTo("{\"to\":\"jane@example.com\"}");
        assertThat(message.path("tool_calls")
                        .path(1)
                        .path("custom")
                        .path("input")
                        .stringValue())
                .isEqualTo("card 4111111111111111");
        assertThat(answer.path("choices")
                        .path(1)
                        .path("message")
                        .path("content")
                        .stringValue())
                .isEqualTo("4111111111111111");
    }

    @Test
    void escapesAValueRestoredIntoToolArguments() {
        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.EMAIL));
        redactor.redact(QUOTED);
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":"
                + "\"{\\\"to\\\":\\\"[EMAIL_1]\\\"}\"}}]}}]}";

        JsonNode answer = ChatRequest.JSON.readTree(response(json)
                .restored(WireFormat.OPENAI, redactor.placeholders())
                .body());

        String arguments = answer.path("choices")
                .path(0)
                .path("message")
                .path("tool_calls")
                .path(0)
                .path("function")
                .path("arguments")
                .stringValue();
        assertThat(ChatRequest.JSON.readTree(arguments).path("to").stringValue())
                .isEqualTo(QUOTED.substring(QUOTED.indexOf('j'), QUOTED.length() - 1));
    }

    @Test
    void restoresAnAnthropicAnswerButLeavesThinkingAsWritten() {
        String json = """
                {"id":"m","content":[
                  {"type":"thinking","thinking":"[EMAIL_1] asked","signature":"c2ln"},
                  {"type":"redacted_thinking","data":"[EMAIL_1]"},
                  {"type":"text","text":"Sure, [EMAIL_1]."},
                  {"type":"tool_use","id":"t","name":"send","input":{"to":["[EMAIL_1]"],"meta":{"cc":"[EMAIL_2]","n":1}}},
                  "odd"],"usage":{"input_tokens":1}}
                """;

        JsonNode content = restored(json, WireFormat.ANTHROPIC).path("content");

        assertThat(content.path(0).path("thinking").stringValue()).isEqualTo("[EMAIL_1] asked");
        assertThat(content.path(1).path("data").stringValue()).isEqualTo("[EMAIL_1]");
        assertThat(content.path(2).path("text").stringValue()).isEqualTo("Sure, jane@example.com.");
        assertThat(content.path(3).path("input").path("to").path(0).stringValue())
                .isEqualTo(JANE);
        assertThat(content.path(3).path("input").path("meta").path("cc").stringValue())
                .isEqualTo("bob@example.org");
        assertThat(content.path(3).path("input").path("meta").path("n").intValue())
                .isEqualTo(1);
    }

    @Test
    void leavesAResponseAloneWhenNothingWasRedacted() {
        ProviderResponse response = response("not json");

        assertThat(response.restored(WireFormat.OPENAI, Placeholders.NONE)).isSameAs(response);
        assertThatThrownBy(() -> response.restored(WireFormat.OPENAI, PLACEHOLDERS))
                .isInstanceOf(InvalidProviderResponseException.class);
    }

    @Test
    void restoresAPlaceholderSplitAcrossOpenAiChunks() {
        StreamRewriter rewriter = new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS);

        List<String> relayed = new ArrayList<>();
        relayed.add(relay(rewriter, openAiChunk(0, "{\"content\":\"Mail [EM\"}", null)));
        relayed.add(relay(rewriter, openAiChunk(0, "{\"content\":\"AIL_1] now [\"}", null)));
        relayed.add(relay(rewriter, openAiChunk(0, "{}", "\"stop\"")));
        relayed.add(relay(rewriter, "data: [DONE]"));

        assertThat(relayed.get(0)).contains("\"content\":\"Mail \"");
        assertThat(relayed.get(1)).contains("\"content\":\"jane@example.com now \"");
        assertThat(relayed.get(2)).contains("\"delta\":{\"content\":\"[\"}").contains("\"finish_reason\":\"stop\"");
        assertThat(relayed.get(3)).isEqualTo("data: [DONE]\n\n");
        assertThat(rewriter.finish()).isEmpty();
    }

    @Test
    void releasesHeldTextBeforeDoneWhenNoChunkFinishes() {
        StreamRewriter rewriter = new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS);

        relay(rewriter, openAiChunk(0, "{\"content\":\"[EMAIL_\"}", null));
        relay(rewriter, openAiChunk(1, "{\"content\":\"ok\"}", null));
        String done = relay(rewriter, "data: [DONE]");

        assertThat(done)
                .isEqualTo("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"[EMAIL_\"},\"finish_reason\":null}]}\n\n"
                        + "data: [DONE]\n\n");
    }

    @Test
    void releasesHeldTextWhenTheStreamEndsEarly() {
        StreamRewriter openAi = new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS);
        StreamRewriter anthropic = new StreamRewriter(WireFormat.ANTHROPIC, "claude", false, PLACEHOLDERS);

        relay(openAi, openAiChunk(0, "{\"content\":\"[CA\"}", null));
        relay(anthropic, anthropicDelta(0, "text_delta", "text", "[CA"));

        assertThat(new String(openAi.finish().orElseThrow(), StandardCharsets.UTF_8))
                .contains("\"delta\":{\"content\":\"[CA\"}");
        assertThat(new String(anthropic.finish().orElseThrow(), StandardCharsets.UTF_8))
                .isEqualTo("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"[CA\"}}\n\n");
        assertThat(openAi.finish()).isEmpty();
        assertThat(new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS).finish())
                .isEmpty();
    }

    @Test
    void restoresStreamedToolArgumentsWithEscaping() {
        Redactor redactor = new Redactor(Set.of(), Set.of(Detector.EMAIL));
        redactor.redact(QUOTED);
        StreamRewriter rewriter = new StreamRewriter(WireFormat.OPENAI, "gpt", false, redactor.placeholders());

        String first = relay(
                rewriter,
                openAiChunk(
                        0,
                        "{\"tool_calls\":[{\"index\":0,\"id\":\"t\",\"function\":{\"name\":\"send\","
                                + "\"arguments\":\"{\\\"to\\\":\\\"[EMAI\"}},{\"index\":1,\"function\":{\"arguments\":\"[\"}}]}",
                        null));
        String second = relay(
                rewriter,
                openAiChunk(
                        0,
                        "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"L_1]\\\"}\"}}]}",
                        "\"tool_calls\""));

        String arguments = argumentsOf(first, 0) + argumentsOf(second, 0);
        assertThat(ChatRequest.JSON.readTree(arguments).path("to").stringValue())
                .isEqualTo("jane@example.com");
        assertThat(argumentsOf(second, 1)).isEqualTo("[");
    }

    @Test
    void restoresAnthropicTextAndToolInputAndLeavesThinkingAlone() {
        StreamRewriter rewriter = new StreamRewriter(WireFormat.ANTHROPIC, "claude", false, PLACEHOLDERS);

        String start = relay(
                rewriter,
                "event: content_block_start",
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"[EMA\"}}");
        String text = relay(rewriter, anthropicDelta(0, "text_delta", "text", "IL_1] and [CA"));
        String stop =
                relay(rewriter, "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}");
        String thinking = relay(rewriter, anthropicDelta(1, "thinking_delta", "thinking", "[EMAIL_1]"));
        String json = relay(rewriter, anthropicDelta(2, "input_json_delta", "partial_json", "{\"to\":\"[EMAIL_2"));
        String jsonEnd = relay(rewriter, anthropicDelta(2, "input_json_delta", "partial_json", "]\"}"));
        String tool = relay(
                rewriter,
                "data: {\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"tool_use\","
                        + "\"input\":{\"to\":\"[EMAIL_1]\"}}}");

        assertThat(start).contains("\"text\":\"\"");
        assertThat(text).contains("\"text\":\"jane@example.com and \"");
        assertThat(stop)
                .isEqualTo("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"[CA\"}}\n\n"
                        + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n");
        assertThat(thinking).contains("\"thinking\":\"[EMAIL_1]\"");
        assertThat(json).contains("\"partial_json\":\"{\\\"to\\\":\\\"\"");
        assertThat(jsonEnd).contains("\"partial_json\":\"bob@example.org\\\"}\"");
        assertThat(tool).contains("\"to\":\"jane@example.com\"");
        assertThat(rewriter.finish()).isEmpty();
    }

    @Test
    void keepsHeldTextOutOfAHiddenUsageChunk() {
        StreamRewriter rewriter = new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS);

        relay(rewriter, openAiChunk(0, "{\"content\":\"[EMA\"}", null));
        Optional<byte[]> usage = rewriter.rewrite(
                List.of("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

        assertThat(usage).isEmpty();
        assertThat(relay(rewriter, "data: [DONE]")).contains("\"content\":\"[EMA\"");
    }

    @Test
    void restoresStreamsSplitAnywhereLikeTheWholeAnswer() {
        Random random = new Random(42);
        String answer = "Dear [EMAIL_1], your card [CARD_1] [is] [EMAIL_9] cc [EMAIL_2][EMAIL_1]. [";
        String expected = PLACEHOLDERS.restore(answer);
        for (int run = 0; run < 2_000; run++) {
            List<String> pieces = split(answer, random);
            StreamRewriter openAi = new StreamRewriter(WireFormat.OPENAI, "gpt", false, PLACEHOLDERS);
            StreamRewriter anthropic = new StreamRewriter(WireFormat.ANTHROPIC, "claude", false, PLACEHOLDERS);
            StringBuilder openAiText = new StringBuilder();
            StringBuilder anthropicText = new StringBuilder();
            for (String piece : pieces) {
                String escaped = ChatRequest.JSON.writeValueAsString(piece);
                openAiText.append(texts(relay(openAi, openAiChunk(0, "{\"content\":" + escaped + "}", null))));
                anthropicText.append(texts(relay(
                        anthropic,
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\","
                                + "\"text\":" + escaped + "}}")));
            }
            openAiText.append(texts(relay(openAi, openAiChunk(0, "{}", "\"stop\""))));
            anthropicText.append(texts(relay(anthropic, "data: {\"type\":\"content_block_stop\",\"index\":0}")));

            assertThat(openAiText.toString()).as("OpenAI split %s", pieces).isEqualTo(expected);
            assertThat(anthropicText.toString())
                    .as("Anthropic split %s", pieces)
                    .isEqualTo(expected);
        }
    }

    private static List<String> split(String text, Random random) {
        List<String> pieces = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int next = Math.min(text.length(), at + 1 + random.nextInt(6));
            pieces.add(text.substring(at, next));
            at = next;
        }
        return pieces;
    }

    private static String texts(String relayed) {
        StringBuilder text = new StringBuilder();
        for (String line : relayed.split("\n", -1)) {
            if (!line.startsWith("data: {")) {
                continue;
            }
            JsonNode data = ChatRequest.JSON.readTree(line.substring("data: ".length()));
            text.append(
                    data.path("choices").path(0).path("delta").path("content").asString(""));
            text.append(data.path("delta").path("text").asString(""));
        }
        return text.toString();
    }

    private static String argumentsOf(String relayed, int tool) {
        JsonNode data =
                ChatRequest.JSON.readTree(relayed.substring("data: ".length()).strip());
        for (JsonNode call : data.path("choices").path(0).path("delta").path("tool_calls")) {
            if (call.path("index").asInt() == tool) {
                return call.path("function").path("arguments").asString("");
            }
        }
        return "";
    }

    private static String openAiChunk(int choice, String delta, @Nullable String finish) {
        return "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-5\","
                + "\"choices\":[{\"index\":" + choice + ",\"delta\":" + delta + ",\"finish_reason\":" + finish + "}]}";
    }

    private static String[] anthropicDelta(int index, String type, String field, String text) {
        ObjectNode data = ChatRequest.JSON
                .createObjectNode()
                .put("type", "content_block_delta")
                .put("index", index);
        data.putObject("delta").put("type", type).put(field, text);
        return new String[] {"event: content_block_delta", "data: " + ChatRequest.JSON.writeValueAsString(data)};
    }

    private static String relay(StreamRewriter rewriter, String... lines) {
        return rewriter.rewrite(List.of(lines))
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .orElse("");
    }
}
