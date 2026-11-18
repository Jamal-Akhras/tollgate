package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class UsageApiTest extends ServerTestSupport {

    @Test
    void sumsTheAuditTrailForEachTenant() throws Exception {
        assertThat(answeredCall().statusCode()).isEqualTo(200);

        HttpResponse<String> usage = admin("/usage?by=tenant");

        assertThat(usage.statusCode()).isEqualTo(200);
        assertThat(usage.body())
                .contains("\"by\":\"tenant\"")
                .contains("\"key\":\"support-bot\"")
                .containsPattern("\"calls\":[1-9]");
        assertThat(admin("/usage?by=day").body()).contains("\"by\":\"day\"");
    }

    @Test
    void groupsUsageByUser() throws Exception {
        HttpResponse<String> usage = admin("/usage?by=user");

        assertThat(usage.statusCode()).isEqualTo(200);
        assertThat(usage.body()).contains("\"by\":\"user\"");
    }

    @Test
    void explainsABadQuery() throws Exception {
        HttpResponse<String> week = admin("/usage?by=week");
        assertThat(week.statusCode()).isEqualTo(400);
        assertThat(week.body())
                .contains("\"code\":\"invalid_request\"")
                .contains("by must be one of tenant, model, key, user, day, was week");
        assertThat(admin("/usage?from=yesterday").statusCode()).isEqualTo(400);
    }

    @Test
    void showsEachBudget() throws Exception {
        HttpResponse<String> budgets = admin("/budgets");

        assertThat(budgets.statusCode()).isEqualTo(200);
        assertThat(budgets.body())
                .contains("\"tenant\":\"metered\"")
                .contains("\"period\":\"total\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"limit\":\"0.01\"")
                .contains("\"reserved\":\"0\"");
    }
}
