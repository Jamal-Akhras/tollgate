package io.github.jamalakhras.tollgate.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.micrometer.core.instrument.Metrics;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class AttributionTest extends GatewayTestSupport {

    private static final String BODY = "{\"model\":\"qwen3\",\"messages\":[]}";
    private static final String USER_HEADER = "X-OpenWebUI-User-Id";
    private static final String USER = "6f1c-user";

    private static String hmacOf(String value) {
        return SIGNING_KEYS.hmac(value.getBytes(UTF_8));
    }

    @Test
    void recordsTheHmacOfTheUserHeaderForATenantThatNamesIt() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> response = chat(CHAT_KEY, BODY, USER_HEADER, USER);

        assertThat(response.statusCode()).isEqualTo(200);
        Intent intent = intentOf(response);
        assertThat(intent.user()).isEqualTo(hmacOf(USER));
        assertThat(AuditJson.write(intent)).startsWith("{\"v\":4,").doesNotContain(USER);
    }

    @Test
    void ignoresTheHeaderForATenantThatDoesNotNameIt() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> response = chat(KEY, BODY, USER_HEADER, USER);

        assertThat(response.statusCode()).isEqualTo(200);
        Intent intent = intentOf(response);
        assertThat(intent.user()).isNull();
        assertThat(AuditJson.write(intent)).startsWith("{\"v\":1,");
    }

    @Test
    void leavesARequestWithoutTheHeaderUnattributed() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        HttpResponse<String> missing = chat(CHAT_KEY, BODY);
        HttpResponse<String> blank = chat(CHAT_KEY, BODY, USER_HEADER, " ");

        assertThat(missing.statusCode()).isEqualTo(200);
        assertThat(intentOf(missing).user()).isNull();
        assertThat(blank.statusCode()).isEqualTo(200);
        assertThat(intentOf(blank).user()).isNull();
    }

    @Test
    void doesNotSendTheHeaderToTheProvider() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        chat(CHAT_KEY, BODY, USER_HEADER, USER);

        assertThat(PROVIDER.received().getFirst().headers().keySet())
                .noneMatch(name -> name.equalsIgnoreCase("x-openwebui-user-id"));
    }

    @Test
    void recordsTheUserOnARefusalToo() throws Exception {
        String body = "{\"model\":\"qwen3\",\"messages\":[],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"run_command\",\"parameters\":{}}}]}";

        HttpResponse<String> response = chat(CHAT_KEY, body, USER_HEADER, USER);

        assertThat(response.statusCode()).isEqualTo(403);
        Intent intent = intentOf(response);
        assertThat(intent.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(intent.user()).isEqualTo(hmacOf(USER));
    }

    @Test
    void keepsTheUserOutOfTheMetrics() throws Exception {
        PROVIDER.respond(200, QWEN_ANSWER);

        chat(CHAT_KEY, BODY, USER_HEADER, USER);

        assertThat(Metrics.globalRegistry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain(USER, hmacOf(USER));
    }
}
