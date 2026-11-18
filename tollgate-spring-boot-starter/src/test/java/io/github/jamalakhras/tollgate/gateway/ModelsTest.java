package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ModelsTest extends GatewayTestSupport {

    @Test
    void listsTheOpenAiModelsTheTenantMayUse() throws Exception {
        HttpResponse<String> response = get(Endpoints.OPENAI_MODELS, "Authorization", "Bearer " + KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"qwen3\",\"object\":\"model\",\"created\":0,\"owned_by\":\"tollgate\"},"
                        + "{\"id\":\"slow-model\",\"object\":\"model\",\"created\":0,\"owned_by\":\"tollgate\"},"
                        + "{\"id\":\"offline-model\",\"object\":\"model\",\"created\":0,\"owned_by\":\"tollgate\"}]}");
    }

    @Test
    void requiresAKey() throws Exception {
        HttpResponse<String> response = get(Endpoints.OPENAI_MODELS);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"invalid_api_key\"");
    }

    @Test
    void listsTheAnthropicModelsTheTenantMayUse() throws Exception {
        HttpResponse<String> response = get(Endpoints.ANTHROPIC_MODELS, "x-api-key", KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .isEqualTo("{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet\","
                        + "\"display_name\":\"claude-sonnet\",\"created_at\":\"1970-01-01T00:00:00Z\"}],"
                        + "\"has_more\":false,\"first_id\":\"claude-sonnet\",\"last_id\":\"claude-sonnet\"}");
    }

    @Test
    void listsNoAnthropicModelsForATenantWithOnlyOpenAiOnes() throws Exception {
        HttpResponse<String> response = get(Endpoints.ANTHROPIC_MODELS, "x-api-key", METERED_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"data\":[],\"has_more\":false,\"first_id\":null,\"last_id\":null}");
    }

    @Test
    void requiresAKeyOnTheAnthropicListInAnthropicsFormat() throws Exception {
        HttpResponse<String> response = get(Endpoints.ANTHROPIC_MODELS);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"type\":\"authentication_error\"");
        assertThat(response.headers().firstValue(Gateway.ERROR_HEADER)).contains("invalid_api_key");
    }
}
