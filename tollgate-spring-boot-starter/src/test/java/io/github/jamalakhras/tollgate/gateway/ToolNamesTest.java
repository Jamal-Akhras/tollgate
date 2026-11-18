package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ToolNamesTest extends GatewayTestSupport {

    private HttpResponse<String> chat(String tool, ApiKey key) throws Exception {
        return chat(key, bodyWith(tool));
    }

    private static String bodyWith(String tool) {
        return "{\"model\":\"qwen3\",\"messages\":[],\"tools\":[{\"type\":\"function\"," + "\"function\":{\"name\":\""
                + tool + "\",\"parameters\":{}}}]}";
    }

    private void assertRefused(String tool) throws Exception {
        HttpResponse<String> response = chat(tool, CHAT_KEY);

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
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> response = chat("get_weather", CHAT_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received()).hasSize(1);
        assertThat(PROVIDER.received().getFirst().body())
                .contains(
                        "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"parameters\":{}}}]");
    }

    @Test
    void leavesTenantsWithoutTheGuardAlone() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> response = chat("run_command", CODER_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
