package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.config.OutputTokens;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatRequestTest {

    private static final OutputTokens LIMIT = new OutputTokens(256, 1_024);

    private static ChatRequest parse(String json) {
        return ChatRequest.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(ChatRequest.Forwarded forwarded) {
        return new String(forwarded.body(), StandardCharsets.UTF_8);
    }

    @Test
    void capsEachWebSearchToolAndCountsTheSearches() {
        ChatRequest request = parse("{\"model\":\"claude\",\"tools\":[{\"type\":\"web_search_20250305\"},"
                + "{\"type\":\"web_search_20250305\",\"max_uses\":9},{\"type\":\"web_search_20250305\",\"max_uses\":1},"
                + "{\"name\":\"emit\"},\"odd\"]}");

        ChatRequest.Forwarded forwarded = request.forward("claude-sonnet-4-5", WireFormat.ANTHROPIC, null, 3, true);

        assertThat(forwarded.webSearches()).isEqualTo(7);
        assertThat(text(forwarded))
                .contains("{\"type\":\"web_search_20250305\",\"max_uses\":3},"
                        + "{\"type\":\"web_search_20250305\",\"max_uses\":3},"
                        + "{\"type\":\"web_search_20250305\",\"max_uses\":1},{\"name\":\"emit\"},\"odd\"]");
        assertThat(request.forward("claude-sonnet-4-5", WireFormat.ANTHROPIC, null, 3, true)
                        .webSearches())
                .isEqualTo(7);
    }

    @Test
    void leavesToolsAloneWithoutWebSearchOrOnOpenAi() {
        String tools = "\"tools\":[{\"type\":\"web_search_20250305\"}]";

        assertThat(parse("{\"model\":\"m\"," + tools + "}")
                        .forward("m", WireFormat.OPENAI, null, 3, true)
                        .webSearches())
                .isZero();
        assertThat(parse("{\"model\":\"m\"}")
                        .forward("m", WireFormat.ANTHROPIC, null, 3, true)
                        .webSearches())
                .isZero();
        assertThat(text(parse("{\"model\":\"m\",\"tools\":[{\"type\":\"web_search_20250305\",\"max_uses\":\"2\"}]}")
                        .forward("m", WireFormat.ANTHROPIC, null, 3, true)))
                .contains("\"max_uses\":3");
    }

    @Test
    void countsChoicesOnlyForOpenAi() {
        assertThat(parse("{\"model\":\"m\"}")
                        .forward("m", WireFormat.OPENAI, null, 0, true)
                        .choices())
                .isOne();
        assertThat(parse("{\"model\":\"m\",\"n\":5}")
                        .forward("m", WireFormat.OPENAI, null, 0, true)
                        .choices())
                .isEqualTo(5);
        assertThat(parse("{\"model\":\"m\",\"n\":128}")
                        .forward("m", WireFormat.OPENAI, null, 0, true)
                        .choices())
                .isEqualTo(128);
        assertThat(parse("{\"model\":\"m\",\"n\":null}")
                        .forward("m", WireFormat.OPENAI, null, 0, true)
                        .choices())
                .isOne();
        assertThat(parse("{\"model\":\"m\",\"n\":5}")
                        .forward("m", WireFormat.ANTHROPIC, null, 0, true)
                        .choices())
                .isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "129", "\"2\"", "1.5", "-1"})
    void refusesChoiceCountsOutsideOneTo128(String n) {
        ChatRequest request = parse("{\"model\":\"m\",\"n\":" + n + "}");

        assertThatThrownBy(() -> request.forward("m", WireFormat.OPENAI, null, 0, true))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("'n' must be a whole number from 1 to 128");
    }

    @Test
    void countsInlineImages() {
        String openAi = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AA\"}},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\"}},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,BB\"}}]}]}";
        String anthropic = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":["
                + "{\"type\":\"image\",\"source\":{\"type\":\"base64\"}},"
                + "{\"type\":\"image\",\"source\":{\"type\":\"url\"}},"
                + "{\"type\":\"document\",\"source\":{\"type\":\"base64\"}}]}]}";

        assertThat(parse(openAi).forward("m", WireFormat.OPENAI, null, 0, true).inlineImages())
                .isEqualTo(2);
        assertThat(parse(anthropic)
                        .forward("m", WireFormat.ANTHROPIC, null, 0, true)
                        .inlineImages())
                .isOne();
    }

    @Test
    void readsTheThinkingBudget() {
        assertThat(parse("{\"model\":\"m\",\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}}")
                        .thinkingBudget())
                .isEqualTo(2_048);
        assertThat(parse("{\"model\":\"m\"}").thinkingBudget()).isZero();
        assertThat(parse("{\"model\":\"m\",\"thinking\":{\"budget_tokens\":-5}}")
                        .thinkingBudget())
                .isZero();
        assertThat(parse("{\"model\":\"m\",\"thinking\":{\"budget_tokens\":\"9\"}}")
                        .thinkingBudget())
                .isZero();
    }

    @Test
    void fillsTheOutputCapOnlyWhenAsked() {
        ChatRequest request = parse("{\"model\":\"m\"}");

        assertThat(text(request.forward("m", WireFormat.OPENAI, LIMIT, 0, false)))
                .isEqualTo("{\"model\":\"m\"}");
        assertThat(request.forward("m", WireFormat.OPENAI, LIMIT, 0, false).maxOutputTokens())
                .isEmpty();
        assertThat(text(request.forward("m", WireFormat.OPENAI, LIMIT, 0, true)))
                .contains("\"max_tokens\":256");
        assertThat(text(parse("{\"model\":\"m\",\"max_tokens\":5000}")
                        .forward("m", WireFormat.OPENAI, LIMIT, 0, false)))
                .contains("\"max_tokens\":1024");
    }

    @Test
    void readsTheModel() {
        assertThat(parse("{\"model\":\"qwen3\",\"messages\":[]}").model()).isEqualTo("qwen3");
    }

    @Test
    void forwardsEveryFieldAndReplacesOnlyTheModel() {
        ChatRequest request = parse("""
                {"model":"qwen3","temperature":0.70,"seed":12345678901234567890,
                 "response_format":{"type":"json_schema","json_schema":{"name":"out","schema":{}}},
                 "messages":[{"role":"user","content":"Hi \\u00e9"}]}""");

        ChatRequest.Forwarded forwarded = request.forward("qwen3:32b", WireFormat.OPENAI, null, 0, true);

        String expected = "{\"model\":\"qwen3:32b\",\"temperature\":0.70,\"seed\":12345678901234567890,"
                + "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"out\",\"schema\":{}}},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi é\"}]}";
        assertThat(text(forwarded)).isEqualTo(expected);
        assertThat(forwarded.size()).isEqualTo(expected.getBytes(StandardCharsets.UTF_8).length);
        assertThat(forwarded.maxOutputTokens()).isEmpty();
        assertThat(request.model()).isEqualTo("qwen3");
    }

    @Test
    void setsTheDefaultOutputLimitWhenTheRequestHasNone() {
        ChatRequest.Forwarded forwarded =
                parse("{\"model\":\"qwen3\"}").forward("qwen3:32b", WireFormat.OPENAI, LIMIT, 0, true);

        assertThat(text(forwarded)).isEqualTo("{\"model\":\"qwen3:32b\",\"max_tokens\":256}");
        assertThat(forwarded.maxOutputTokens()).hasValue(256);
    }

    @Test
    void capsTheRequestedOutputLimitInPlace() {
        ChatRequest.Forwarded forwarded = parse("{\"model\":\"claude\",\"max_tokens\":5000,\"messages\":[]}")
                .forward("claude-sonnet-4-5", WireFormat.ANTHROPIC, LIMIT, 0, true);

        assertThat(text(forwarded)).isEqualTo("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":1024,\"messages\":[]}");
        assertThat(forwarded.maxOutputTokens()).hasValue(1_024);
    }

    @Test
    void keepsALowerRequestedLimit() {
        ChatRequest.Forwarded forwarded = parse("{\"model\":\"qwen3\",\"max_tokens\":64}")
                .forward("qwen3:32b", WireFormat.OPENAI, LIMIT, 0, true);

        assertThat(forwarded.maxOutputTokens()).hasValue(64);
    }

    @Test
    void capsBothOpenAiOutputFields() {
        ChatRequest.Forwarded forwarded = parse("{\"model\":\"o3\",\"max_tokens\":100,\"max_completion_tokens\":9000}")
                .forward("o3", WireFormat.OPENAI, LIMIT, 0, true);

        assertThat(text(forwarded)).isEqualTo("{\"model\":\"o3\",\"max_tokens\":100,\"max_completion_tokens\":1024}");
        assertThat(forwarded.maxOutputTokens()).hasValue(1_024);
    }

    @Test
    void leavesTheNewerOpenAiFieldAloneOnAnthropicRequests() {
        ChatRequest.Forwarded forwarded = parse("{\"model\":\"claude\",\"max_completion_tokens\":9000}")
                .forward("claude-sonnet-4-5", WireFormat.ANTHROPIC, LIMIT, 0, true);

        assertThat(text(forwarded))
                .isEqualTo("{\"model\":\"claude-sonnet-4-5\",\"max_completion_tokens\":9000,\"max_tokens\":256}");
    }

    @Test
    void reportsTheRequestedLimitWhenThereIsNoCap() {
        ChatRequest.Forwarded forwarded = parse("{\"model\":\"qwen3\",\"max_tokens\":5000}")
                .forward("qwen3:32b", WireFormat.OPENAI, null, 0, true);

        assertThat(forwarded.maxOutputTokens()).hasValue(5_000);
        assertThat(text(forwarded)).contains("\"max_tokens\":5000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "1.5", "\"100\"", "true", "99999999999999999999"})
    void rejectsOutputLimitsThatAreNotPositiveWholeNumbers(String value) {
        ChatRequest request = parse("{\"model\":\"qwen3\",\"max_tokens\":" + value + "}");

        assertThatThrownBy(() -> request.forward("qwen3:32b", WireFormat.OPENAI, LIMIT, 0, true))
                .isInstanceOfSatisfying(
                        InvalidRequestException.class,
                        e -> assertThat(e.param()).isEqualTo("max_tokens"))
                .hasMessage("'max_tokens' must be a positive whole number");
    }

    @Test
    void acceptsAnExplicitNullOutputLimit() {
        assertThat(parse("{\"model\":\"qwen3\",\"max_tokens\":null}")
                        .forward("qwen3:32b", WireFormat.OPENAI, LIMIT, 0, true)
                        .maxOutputTokens())
                .hasValue(256);
    }

    @Test
    void rejectsDuplicateFieldsSoTheProviderCannotReadADifferentModel() {
        assertThatThrownBy(() -> parse("{\"model\":\"qwen3\",\"model\":\"gpt-5\"}"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Duplicate Object property \"model\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"model\":\"qwen3\"", "not json"})
    void rejectsInvalidJson(String body) {
        assertThatThrownBy(() -> parse(body)).hasMessageStartingWith("The request body is not valid JSON");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "\"model\"", "42"})
    void requiresAnObject(String body) {
        assertThatThrownBy(() -> parse(body)).hasMessage("The request body must be a JSON object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"model\":7}", "{\"model\":\" \"}", "{\"model\":null}"})
    void requiresAModelName(String body) {
        assertThatThrownBy(() -> parse(body))
                .isInstanceOfSatisfying(
                        InvalidRequestException.class,
                        e -> assertThat(e.param()).isEqualTo("model"))
                .hasMessage("'model' is required and must be a string");
    }

    @Test
    void streamsWhenStreamIsTrue() {
        assertThat(parse("{\"model\":\"qwen3\",\"stream\":true}").isStreaming()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"true\"", "1", "{}"})
    void refusesAStreamFlagThatIsNotABoolean(String stream) {
        assertThatThrownBy(() -> parse("{\"model\":\"qwen3\",\"stream\":" + stream + "}"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("'stream' must be true or false");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"model\":\"qwen3\"}",
                "{\"model\":\"qwen3\",\"stream\":false}",
                "{\"model\":\"qwen3\",\"stream\":null}"
            })
    void recognisesNonStreamingRequests(String body) {
        assertThat(parse(body).isStreaming()).isFalse();
    }
}
