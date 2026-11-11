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
}
