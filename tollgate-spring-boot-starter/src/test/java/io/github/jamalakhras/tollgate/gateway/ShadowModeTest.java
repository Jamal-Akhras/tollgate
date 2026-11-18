package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ShadowModeTest extends GatewayTestSupport {

    private HttpResponse<String> chat(String model, String dataClass) throws Exception {
        return chat(WATCHED_KEY, "{\"model\":\"" + model + "\",\"messages\":[]}", Gateway.DATA_CLASS_HEADER, dataClass);
    }

    private static double monitored(String guard) {
        return count(Gateway.GUARDS_MONITORED_METRIC, "tenant", "watched", "guard", guard);
    }

    @Test
    void forwardsARequestAMonitoredGuardWouldRefuseAndRecordsWhatItWouldHaveDone() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);
        double before = monitored("request-size");

        HttpResponse<String> response = chat("qwen3", "internal");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(PROVIDER.received()).hasSize(1);
        Intent intent = intentOf(response);
        assertThat(intent.decision()).isEqualTo(Decision.ALLOW);
        assertThat(intent.steps())
                .filteredOn(step -> !step.guard().equals("request-features"))
                .extracting(GuardStep::guard, GuardStep::monitored, step -> step.decision() instanceof Decision.Deny)
                .containsExactly(tuple("request-size", true, true), tuple("data-class", false, false));
        assertThat(AuditJson.write(intent)).startsWith("{\"v\":3,");
        assertThat(monitored("request-size")).isEqualTo(before + 1);
    }

    @Test
    void stillRefusesWithAnEnforcingGuardAfterAMonitoredOne() throws Exception {
        HttpResponse<String> response = chat("cloud-model", "confidential");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(PROVIDER.received()).isEmpty();
        Intent intent = intentOf(response);
        assertThat(intent.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(intent.steps())
                .filteredOn(step -> !step.guard().equals("request-features"))
                .extracting(GuardStep::guard, GuardStep::monitored)
                .containsExactly(tuple("request-size", true), tuple("data-class", false));
    }
}
