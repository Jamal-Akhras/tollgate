package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ErrorHeaderTest extends GatewayTestSupport {

    private static final String TOOL_CALL = "{\"model\":\"qwen3\",\"messages\":[],\"tools\":[{\"type\":\"function\","
            + "\"function\":{\"name\":\"run_command\",\"parameters\":{}}}]}";
    private static final String ANTHROPIC_BODY = "{\"model\":\"qwen3\",\"max_tokens\":10,\"messages\":[]}";

    @Test
    void namesPolicyDeniedOnARefusedTool() throws Exception {
        HttpResponse<String> response = chat(CHAT_KEY, TOOL_CALL);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue(Gateway.ERROR_HEADER)).contains("policy_denied");
    }

    @Test
    void namesInvalidApiKeyOnEveryEndpoint() throws Exception {
        HttpResponse<String> models = get(Endpoints.OPENAI_MODELS);
        HttpResponse<String> messages = post(Endpoints.ANTHROPIC_MESSAGES, ANTHROPIC_BODY);

        assertThat(models.statusCode()).isEqualTo(401);
        assertThat(models.headers().firstValue(Gateway.ERROR_HEADER)).contains("invalid_api_key");
        assertThat(messages.statusCode()).isEqualTo(401);
        assertThat(messages.headers().firstValue(Gateway.ERROR_HEADER)).contains("invalid_api_key");
    }

    @Test
    void tellsTheSdksNotToRetryASpentBudget() throws Exception {
        HttpResponse<String> response = chat(FRUGAL_KEY, "{\"model\":\"frugal-model\",\"n\":2,\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue(Gateway.ERROR_HEADER)).contains("budget_exceeded");
        assertThat(response.headers().firstValue("x-should-retry")).contains("false");
    }

    @Test
    void leavesTheHeaderOffAnAnsweredChat() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> response = chat(KEY, "{\"model\":\"qwen3\",\"messages\":[]}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(Gateway.ERROR_HEADER)).isEmpty();
    }
}
