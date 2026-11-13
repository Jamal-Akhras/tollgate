package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ToolNamesTest extends GatewayTestSupport {

    private static final String ANSWER = "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}";

    private HttpResponse<String> chat(String tool, String key) throws Exception {
        return post(Endpoints.OPENAI_CHAT, bodyWith(tool), "Authorization", "Bearer " + key);
    }

    private static String bodyWith(String tool) {
        return "{\"model\":\"qwen3\",\"messages\":[],\"tools\":[{\"type\":\"function\"," + "\"function\":{\"name\":\""
                + tool + "\",\"parameters\":{}}}]}";
    }

    private static Intent intentOf(HttpResponse<String> response) throws Exception {
        return auditRecordsOf(response).stream()
                .filter(Intent.class::isInstance)
                .map(Intent.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private void assertRefused(String tool) throws Exception {
        HttpResponse<String> response = chat(tool, CHAT_KEY.value());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"policy_denied\"").contains(tool);
        assertThat(PROVIDER.received()).isEmpty();
        assertThat(intentOf(response).steps())
                .filteredOn(step -> step.guard().equals("tool-names"))
                .singleElement()
                .extracting(GuardStep::decision)
                .isInstanceOf(Decision.Deny.class);
    }

    @Test
    void refusesARequestThatDefinesARefusedTool() throws Exception {
        assertRefused("run_command");
    }

    @Test
    void refusesAToolThatMatchesAPattern() throws Exception {
        assertRefused("delete_file");
    }

    @Test
    void forwardsARequestWhoseToolsAreAllowed() throws Exception {
        PROVIDER.respond(200, ANSWER);

        HttpResponse<String> response = chat("get_weather", CHAT_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received()).hasSize(1);
        assertThat(PROVIDER.received().getFirst().body())
                .contains(
                        "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{}}}]");
    }

    @Test
    void leavesTenantsWithoutTheGuardAlone() throws Exception {
        PROVIDER.respond(200, ANSWER);

        HttpResponse<String> response = chat("run_command", CODER_KEY.value());

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
