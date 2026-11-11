package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class PolicyTest extends GatewayTestSupport {

    private static final String QWEN = "{\"model\":\"qwen3\",\"messages\":[]}";
    private static final String CLAUDE = "{\"model\":\"claude-sonnet\",\"max_tokens\":64,\"messages\":[]}";

    private HttpResponse<String> chat(String body, String... dataClass) throws IOException, InterruptedException {
        String authorization = "Bearer " + KEY.value();
        return dataClass.length == 0
                ? post(Endpoints.OPENAI_CHAT, body, "Authorization", authorization)
                : post(
                        Endpoints.OPENAI_CHAT,
                        body,
                        "Authorization",
                        authorization,
                        Gateway.DATA_CLASS_HEADER,
                        dataClass[0]);
    }

    @Test
    void keepsConfidentialRequestsOnLocalModels() throws Exception {
        HttpResponse<String> local = chat(QWEN, "confidential");
        HttpResponse<String> remote = post(
                Endpoints.ANTHROPIC_MESSAGES,
                CLAUDE,
                "x-api-key",
                KEY.value(),
                Gateway.DATA_CLASS_HEADER,
                "confidential");

        assertThat(local.statusCode()).isEqualTo(200);
        assertThat(remote.statusCode()).isEqualTo(403);
        assertThat(remote.body())
                .isEqualTo("{\"type\":\"error\",\"error\":{\"type\":\"permission_error\",\"message\":"
                        + "\"Data class 'confidential' may go only to regions [local]; the model 'claude-sonnet' is in"
                        + " region 'us'\"}}");
        assertThat(PROVIDER.received()).hasSize(1);
    }

    @Test
    void refusesToLoosenTheTenantsDataClass() throws Exception {
        HttpResponse<String> response = chat(QWEN, "public");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body())
                .contains("\"code\":\"policy_denied\"", "is less strict than this tenant's 'internal'");
        assertThat(PROVIDER.received()).isEmpty();
    }

    @Test
    void rejectsUnknownDataClasses() throws Exception {
        HttpResponse<String> response = chat(QWEN, "secret");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"invalid_data_class\"");
    }

    @Test
    void appliesThePolicySizeLimit() throws Exception {
        String content = "x".repeat(900);

        HttpResponse<String> response =
                chat("{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + content + "\"}]}");

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("this policy allows 900", "\"code\":\"request_too_large\"");
        assertThat(PROVIDER.received()).isEmpty();
    }
}
