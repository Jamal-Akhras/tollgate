package io.github.jamalakhras.tollgate.providers;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RequestReviewTest {

    private static RequestFeatures openAi(String fields) {
        return review(WireFormat.OPENAI, "{\"model\":\"m\"," + fields + "}", null);
    }

    private static RequestFeatures openAiContent(String part) {
        return openAi("\"messages\":[{\"role\":\"user\",\"content\":[" + part + "]}]");
    }

    private static RequestFeatures anthropic(String fields, @Nullable String betas) {
        return review(WireFormat.ANTHROPIC, "{\"model\":\"m\",\"max_tokens\":5," + fields + "}", betas);
    }

    private static RequestFeatures anthropicContent(String block) {
        return anthropic("\"messages\":[{\"role\":\"user\",\"content\":[" + block + "]}]", null);
    }

    private static RequestFeatures review(WireFormat format, String json, @Nullable String betas) {
        return ChatRequest.parse(json.getBytes(StandardCharsets.UTF_8)).review(format, betas);
    }

    @Test
    void acceptsEveryReviewedOpenAiField() {
        RequestFeatures features = openAi("""
                "messages":[{"role":"system","content":"Be brief"},{"role":"user","content":[{"type":"text","text":"Hi"}]},
                  {"role":"assistant","content":[{"type":"refusal","refusal":"No"}]}],
                "max_tokens":5,"max_completion_tokens":5,"n":2,"temperature":0.2,"top_p":0.9,"stop":["x"],"seed":1,
                "presence_penalty":0,"frequency_penalty":0,"logit_bias":{},"logprobs":true,"top_logprobs":2,
                "tools":[{"type":"function","function":{"name":"f"}},{"type":"custom","custom":{"name":"g"}}],
                "tool_choice":"auto","parallel_tool_calls":false,"response_format":{"type":"json_object"},
                "reasoning_effort":"low","verbosity":"low","user":"u","safety_identifier":"s","prompt_cache_key":"k",
                "store":false,"stream":false""");

        assertThat(features).isEqualTo(RequestFeatures.NONE);
    }

    @Test
    void acceptsEveryReviewedAnthropicField() {
        RequestFeatures features = anthropic("""
                "system":[{"type":"text","text":"Be brief","cache_control":{"type":"ephemeral"}}],
                "messages":[{"role":"user","content":"Hi"},{"role":"assistant","content":[{"type":"thinking"},
                  {"type":"redacted_thinking"},{"type":"tool_use","name":"emit"}]}],
                "temperature":0.2,"top_p":0.9,"top_k":5,"stop_sequences":["x"],"tool_choice":{"type":"auto"},
                "thinking":{"type":"enabled","budget_tokens":1024},"metadata":{"user_id":"u"},"stream":false,
                "tools":[{"name":"emit","input_schema":{}},{"type":"custom","name":"c"},{"type":"bash_20250124"},
                  {"type":"text_editor_20250728"},{"type":"computer_20250124"},{"type":"memory_20250818"}]""", null);

        assertThat(features).isEqualTo(RequestFeatures.NONE);
    }

    @Test
    void marksUnknownFieldsAsUnreviewed() {
        assertThat(openAi("\"prediction\":{}").unreviewed())
                .containsExactly("The field 'prediction' is not one Tollgate has reviewed");
        assertThat(anthropic("\"context_management\":{}", null).unreviewed())
                .containsExactly("The field 'context_management' is not one Tollgate has reviewed");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "mcp_servers        | it makes the provider connect to other servers",
                "container          | it runs code in a provider sandbox",
                "web_search_options | it sends searches to a search engine",
                "metadata           | it keeps the conversation at the provider",
                "service_tier       | it changes the price Tollgate budgets for",
                "modalities         | audio is priced differently from text",
                "audio              | audio is priced differently from text"
            })
    void refusesFieldsThatSendDataElsewhereOrChangeThePrice(String field, String reason) {
        assertThat(openAi("\"" + field + "\":{}").refused())
                .containsExactly("The field '" + field + "' is not allowed: " + reason);
        assertThat(openAi("\"" + field + "\":{}").unreviewed()).isEmpty();
    }

    @Test
    void refusesMcpServersOnAnthropicToo() {
        assertThat(anthropic("\"mcp_servers\":[]", null).refused())
                .containsExactly(
                        "The field 'mcp_servers' is not allowed: it makes the provider connect to other servers");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "\"false\"", "null"})
    void allowsStoreOnlyAsFalse(String value) {
        assertThat(openAi("\"store\":" + value).refused())
                .containsExactly("The field 'store' is not allowed: it keeps the conversation at the provider");
    }

    @Test
    void refusesOpenAiServerTools() {
        assertThat(openAi("\"tools\":[{\"type\":\"function\"},{\"type\":\"web_search\"},{}]")
                        .refused())
                .containsExactly(
                        "The tool type 'web_search' is not allowed: it runs on the provider, outside Tollgate's view",
                        "The tool type '' is not allowed: it runs on the provider, outside Tollgate's view");
    }

    @ParameterizedTest
    @ValueSource(strings = {"web_search_20250305", "web_fetch_20250910", "code_execution_20250825", "tool_search_x"})
    void refusesAnthropicServerTools(String type) {
        assertThat(anthropic("\"tools\":[{\"type\":\"" + type + "\"}]", null).refused())
                .containsExactly("The tool type '" + type
                        + "' is not allowed: it runs on the provider, outside Tollgate's view");
    }

    @Test
    void checksOpenAiContentParts() {
        assertThat(openAiContent("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AA\"}}"))
                .isEqualTo(RequestFeatures.NONE);
        assertThat(openAiContent("{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x/y.png\"}}")
                        .used())
                .containsExactly(Feature.REMOTE_IMAGES);
        assertThat(openAiContent("{\"type\":\"image_url\",\"image_url\":{\"url\":\"http://x/y.png\"}}")
                        .used())
                .containsExactly(Feature.REMOTE_IMAGES);
        assertThat(openAiContent("{\"type\":\"image_url\",\"image_url\":{\"url\":\"file:///etc/passwd\"}}")
                        .refused())
                .containsExactly("Images must be inline data or an http or https URL");
        assertThat(openAiContent("{\"type\":\"file\",\"file\":{\"file_data\":\"data:application/pdf;base64,AA\"}}"))
                .isEqualTo(RequestFeatures.NONE);
        assertThat(openAiContent("{\"type\":\"file\",\"file\":{\"file_id\":\"file-1\"}}")
                        .used())
                .containsExactly(Feature.PROVIDER_FILES);
        assertThat(openAiContent("{\"type\":\"input_audio\"}").refused())
                .containsExactly("The content type 'input_audio' is not allowed");
    }

    @Test
    void checksAnthropicContentBlocks() {
        assertThat(anthropicContent("{\"type\":\"image\",\"source\":{\"type\":\"base64\"}}"))
                .isEqualTo(RequestFeatures.NONE);
        assertThat(anthropicContent("{\"type\":\"document\",\"source\":{\"type\":\"base64\"}}"))
                .isEqualTo(RequestFeatures.NONE);
        assertThat(anthropicContent("{\"type\":\"document\",\"source\":{\"type\":\"text\"}}"))
                .isEqualTo(RequestFeatures.NONE);
        assertThat(anthropicContent("{\"type\":\"image\",\"source\":{\"type\":\"url\"}}")
                        .used())
                .containsExactly(Feature.REMOTE_IMAGES);
        assertThat(anthropicContent("{\"type\":\"document\",\"source\":{\"type\":\"url\"}}")
                        .used())
                .containsExactly(Feature.REMOTE_DOCUMENTS);
        assertThat(anthropicContent("{\"type\":\"image\",\"source\":{\"type\":\"file\"}}")
                        .used())
                .containsExactly(Feature.PROVIDER_FILES);
        assertThat(anthropicContent("{\"type\":\"image\",\"source\":{\"type\":\"s3\"}}")
                        .refused())
                .containsExactly("The source type 's3' is not allowed");
        assertThat(anthropicContent("{\"type\":\"container_upload\"}").refused())
                .containsExactly("The content type 'container_upload' is not allowed");
    }

    @Test
    void looksInsideToolResultsDocumentsAndTheSystemPrompt() {
        String remoteImage = "{\"type\":\"image\",\"source\":{\"type\":\"url\"}}";

        assertThat(anthropicContent("{\"type\":\"tool_result\",\"content\":[" + remoteImage + "]}")
                        .used())
                .containsExactly(Feature.REMOTE_IMAGES);
        assertThat(anthropicContent("{\"type\":\"document\",\"source\":{\"type\":\"content\",\"content\":["
                                + remoteImage + "]}}")
                        .used())
                .containsExactly(Feature.REMOTE_IMAGES);
        assertThat(anthropic("\"system\":[{\"type\":\"web_search_tool_result\"}]", null)
                        .refused())
                .containsExactly("The content type 'web_search_tool_result' is not allowed");
        assertThat(anthropicContent("{\"type\":\"tool_result\",\"content\":\"text\"}"))
                .isEqualTo(RequestFeatures.NONE);
    }

    @Test
    void reviewsEachAnthropicBeta() {
        RequestFeatures features =
                anthropic("\"messages\":[]", "interleaved-thinking-2025-05-14, ,mcp-client-2025-11-20,");

        assertThat(features.unreviewed())
                .containsExactly(
                        "The anthropic-beta 'interleaved-thinking-2025-05-14' is not one Tollgate has reviewed");
        assertThat(features.refused())
                .containsExactly("The anthropic-beta 'mcp-client-2025-11-20' is not allowed: it switches on a provider"
                        + " ability Tollgate refuses");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "code-execution-2025-08-25",
                "web-fetch-2025-09-10",
                "files-api-2025-04-14",
                "context-1m-2025-08-07"
            })
    void refusesBetasForRefusedAbilities(String beta) {
        assertThat(anthropic("\"messages\":[]", beta).refused()).hasSize(1);
    }

    @Test
    void ignoresBetasOnOpenAiRequests() {
        assertThat(review(WireFormat.OPENAI, "{\"model\":\"m\"}", "mcp-client-2025-11-20"))
                .isEqualTo(RequestFeatures.NONE);
    }
}
