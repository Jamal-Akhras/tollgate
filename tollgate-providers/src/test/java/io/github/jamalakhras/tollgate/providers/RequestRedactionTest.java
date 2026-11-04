package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RequestRedactionTest {

    private static Redactor redactor() {
        return new Redactor(Set.of(Detector.SECRET), Set.of(Detector.EMAIL, Detector.CARD, Detector.PHONE));
    }

    private static String sent(String json, WireFormat format, Redactor redactor) {
        ChatRequest request = ChatRequest.parse(json.getBytes(StandardCharsets.UTF_8));
        return new String(
                request.redacted(format, redactor)
                        .forward("m", format, null, 0, false)
                        .body(),
                StandardCharsets.UTF_8);
    }

    private static JsonNode tree(String json) {
        return ChatRequest.JSON.readTree(json);
    }

    @Test
    void redactsEveryOpenAiTextAndLeavesTheRestAlone() {
        String json = """
                {"model":"gpt","temperature":0.7,"messages":[
                  {"role":"system","content":"Support for jane@example.com"},
                  {"role":"user","content":[
                    {"type":"text","text":"My card is 4111 1111 1111 1111"},
                    {"type":"image_url","image_url":{"url":"https://example.com/jane@example.com.png"}}]},
                  {"role":"assistant","content":null,"refusal":"Not for bob@example.org",
                   "reasoning_content":"jane@example.com wants","tool_calls":[
                    {"id":"call_1","type":"function","function":{"name":"send_email",
                      "arguments":"{\\"to\\": \\"jane@example.com\\", \\"cc\\": [\\"bob@example.org\\"], \\"n\\": 1.50}"}},
                    {"id":"call_2","type":"custom","custom":{"name":"note","input":"call +44 20 7946 0958"}}]},
                  {"role":"tool","tool_call_id":"call_1","content":"Sent to jane@example.com"},
                  {"role":"user","content":[{"type":"refusal","refusal":"jane@example.com"}]}],
                 "tools":[{"type":"function","function":{"name":"send_email","description":"e.g. jane@example.com"}}]}
                """;

        JsonNode sent = tree(sent(json, WireFormat.OPENAI, redactor()));

        JsonNode messages = sent.path("messages");
        assertThat(messages.path(0).path("content").stringValue()).isEqualTo("Support for [EMAIL_1]");
        assertThat(messages.path(1).path("content").path(0).path("text").stringValue())
                .isEqualTo("My card is [CARD_1]");
        assertThat(messages.path(1)
                        .path("content")
                        .path(1)
                        .path("image_url")
                        .path("url")
                        .stringValue())
                .isEqualTo("https://example.com/jane@example.com.png");
        assertThat(messages.path(2).path("refusal").stringValue()).isEqualTo("Not for [EMAIL_2]");
        assertThat(messages.path(2).path("reasoning_content").stringValue()).isEqualTo("[EMAIL_1] wants");
        assertThat(messages.path(2)
                        .path("tool_calls")
                        .path(0)
                        .path("function")
                        .path("arguments")
                        .stringValue())
                .isEqualTo("{\"to\":\"[EMAIL_1]\",\"cc\":[\"[EMAIL_2]\"],\"n\":1.50}");
        assertThat(messages.path(2)
                        .path("tool_calls")
                        .path(1)
                        .path("custom")
                        .path("input")
                        .stringValue())
                .isEqualTo("call [PHONE_1]");
        assertThat(messages.path(3).path("content").stringValue()).isEqualTo("Sent to [EMAIL_1]");
        assertThat(messages.path(4).path("content").path(0).path("refusal").stringValue())
                .isEqualTo("[EMAIL_1]");
        assertThat(sent.path("tools")
                        .path(0)
                        .path("function")
                        .path("description")
                        .stringValue())
                .isEqualTo("e.g. jane@example.com");
        assertThat(sent.path("temperature").decimalValue()).isEqualByComparingTo("0.7");
    }

    @Test
    void leavesToolArgumentsAsWrittenWhenNothingInThemIsRedacted() {
        String arguments = "{\\\"city\\\":  \\\"Paris\\\" }";
        String json = "{\"model\":\"gpt\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"type\":\"function\","
                + "\"function\":{\"name\":\"weather\",\"arguments\":\"" + arguments + "\"}}]}]}";

        JsonNode sent = tree(sent(json, WireFormat.OPENAI, redactor()));

        assertThat(sent.path("messages")
                        .path(0)
                        .path("tool_calls")
                        .path(0)
                        .path("function")
                        .path("arguments")
                        .stringValue())
                .isEqualTo("{\"city\":  \"Paris\" }");
    }

    @Test
    void redactsToolArgumentsThatAreNotJsonAsText() {
        String json = "{\"model\":\"gpt\",\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"type\":\"function\","
                + "\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"to\\\": \\\"jane@example.com\"}},"
                + "{\"type\":\"function\",\"function\":{\"name\":\"g\",\"arguments\":\"\\\"bob@example.org\\\"\"}}]}]}";

        JsonNode calls = tree(sent(json, WireFormat.OPENAI, redactor()))
                .path("messages")
                .path(0)
                .path("tool_calls");

        assertThat(calls.path(0).path("function").path("arguments").stringValue())
                .isEqualTo("{\"to\": \"[EMAIL_1]");
        assertThat(calls.path(1).path("function").path("arguments").stringValue())
                .isEqualTo("\"[EMAIL_2]\"");
    }

    @Test
    void redactsEveryAnthropicTextButLeavesThinkingSigned() {
        String json = """
                {"model":"claude","max_tokens":100,
                 "system":[{"type":"text","text":"Agent for jane@example.com","cache_control":{"type":"ephemeral"}}],
                 "messages":[
                  {"role":"user","content":"Card 4111111111111111"},
                  {"role":"assistant","content":[
                    {"type":"thinking","thinking":"jane@example.com needs help","signature":"c2ln"},
                    {"type":"redacted_thinking","data":"ZW5j"},
                    {"type":"text","text":"Writing to jane@example.com"},
                    {"type":"tool_use","id":"t1","name":"send","input":{"to":["jane@example.com"],"meta":{"cc":"bob@example.org","n":2}}}]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t1","content":"Sent to bob@example.org"},
                    {"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":"jane@example.com"}]},
                    {"type":"document","source":{"type":"text","media_type":"text/plain","data":"Invoice for jane@example.com"}},
                    {"type":"document","source":{"type":"content","content":[{"type":"text","text":"bob@example.org"}]}},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"amFuZUBleGFtcGxlLmNvbQ=="}}]}]}
                """;

        JsonNode sent = tree(sent(json, WireFormat.ANTHROPIC, redactor()));

        assertThat(sent.path("system").path(0).path("text").stringValue()).isEqualTo("Agent for [EMAIL_1]");
        assertThat(sent.path("system")
                        .path(0)
                        .path("cache_control")
                        .path("type")
                        .stringValue())
                .isEqualTo("ephemeral");
        JsonNode messages = sent.path("messages");
        assertThat(messages.path(0).path("content").stringValue()).isEqualTo("Card [CARD_1]");
        JsonNode answer = messages.path(1).path("content");
        assertThat(answer.path(0).path("thinking").stringValue()).isEqualTo("jane@example.com needs help");
        assertThat(answer.path(0).path("signature").stringValue()).isEqualTo("c2ln");
        assertThat(answer.path(1).path("data").stringValue()).isEqualTo("ZW5j");
        assertThat(answer.path(2).path("text").stringValue()).isEqualTo("Writing to [EMAIL_1]");
        assertThat(answer.path(3).path("input").path("to").path(0).stringValue())
                .isEqualTo("[EMAIL_1]");
        assertThat(answer.path(3).path("input").path("meta").path("cc").stringValue())
                .isEqualTo("[EMAIL_2]");
        assertThat(answer.path(3).path("input").path("meta").path("n").intValue())
                .isEqualTo(2);
        JsonNode results = messages.path(2).path("content");
        assertThat(results.path(0).path("content").stringValue()).isEqualTo("Sent to [EMAIL_2]");
        assertThat(results.path(1).path("content").path(0).path("text").stringValue())
                .isEqualTo("[EMAIL_1]");
        assertThat(results.path(2).path("source").path("data").stringValue()).isEqualTo("Invoice for [EMAIL_1]");
        assertThat(results.path(3)
                        .path("source")
                        .path("content")
                        .path(0)
                        .path("text")
                        .stringValue())
                .isEqualTo("[EMAIL_2]");
        assertThat(results.path(4).path("source").path("data").stringValue()).isEqualTo("amFuZUBleGFtcGxlLmNvbQ==");
    }

    @Test
    void redactsASystemPromptWrittenAsText() {
        String json = "{\"model\":\"claude\",\"system\":\"For jane@example.com\",\"messages\":[]}";

        assertThat(tree(sent(json, WireFormat.ANTHROPIC, redactor()))
                        .path("system")
                        .stringValue())
                .isEqualTo("For [EMAIL_1]");
    }

    @Test
    void sendsTheSameBytesEachTurnSoPromptCachingStillWorks() {
        String history = "{\"role\":\"user\",\"content\":\"I am jane@example.com, card 4111111111111111\"},"
                + "{\"role\":\"assistant\",\"content\":\"Thanks jane@example.com\"}";
        String turnOne = "{\"model\":\"gpt\",\"messages\":[" + history + "]}";
        String turnTwo = "{\"model\":\"gpt\",\"messages\":[" + history
                + ",{\"role\":\"user\",\"content\":\"Also bob@example.org\"}]}";

        String first = sent(turnOne, WireFormat.OPENAI, redactor());
        String second = sent(turnTwo, WireFormat.OPENAI, redactor());

        assertThat(second).startsWith(first.substring(0, first.lastIndexOf(']')));
        assertThat(sent(turnOne, WireFormat.OPENAI, redactor())).isEqualTo(first);
    }

    @Test
    void leavesTheOriginalRequestAsItWas() {
        ChatRequest request = ChatRequest.parse(
                "{\"model\":\"gpt\",\"messages\":[{\"role\":\"user\",\"content\":\"jane@example.com\"}]}"
                        .getBytes(StandardCharsets.UTF_8));

        request.redacted(WireFormat.OPENAI, redactor());

        assertThat(new String(
                        request.forward("m", WireFormat.OPENAI, null, 0, false).body(), StandardCharsets.UTF_8))
                .contains("jane@example.com");
    }

    @Test
    void notesARefusedSecretAnywhereInTheRequest() {
        Redactor redactor = redactor();
        String key = "sk-proj-" + "a1B2".repeat(12);
        String json = "{\"model\":\"claude\",\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"t\",\"name\":\"f\",\"input\":{\"deep\":[{\"key\":\"" + key + "\"}]}}]}]}";

        sent(json, WireFormat.ANTHROPIC, redactor);

        assertThat(redactor.refused()).contains(Detector.SECRET);
        assertThat(redactor.values()).isEqualTo(Map.of());
    }

    @Test
    void ignoresShapesItDoesNotKnow() {
        String json = "{\"model\":\"gpt\",\"messages\":[\"odd\",{\"role\":\"user\",\"content\":[\"odd\",1]},"
                + "{\"role\":\"assistant\",\"tool_calls\":[{\"function\":{\"arguments\":7}},\"odd\"]}]}";
        String anthropic = "{\"model\":\"claude\",\"messages\":[\"odd\",{\"role\":\"user\",\"content\":[\"odd\","
                + "{\"type\":\"document\",\"source\":\"odd\"},{\"type\":\"tool_use\",\"input\":[\"jane@example.com\",[1]]}]}]}";

        assertThat(tree(sent(json, WireFormat.OPENAI, redactor()))
                        .path("messages")
                        .path(0)
                        .stringValue())
                .isEqualTo("odd");
        assertThat(sent(anthropic, WireFormat.ANTHROPIC, redactor())).contains("[\"[EMAIL_1]\",[1]]");
    }
}
