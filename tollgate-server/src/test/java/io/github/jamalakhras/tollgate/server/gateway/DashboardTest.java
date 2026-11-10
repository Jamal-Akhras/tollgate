package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DashboardTest {

    private static final Path DASHBOARD = Path.of("..", "docs", "grafana", "tollgate.json");
    private static final Pattern METRIC = Pattern.compile("((?:tollgate|gen_ai)_[a-z_]+)\\{");
    private static final Pattern SUFFIX = Pattern.compile("(_seconds)?(_bucket|_total|_sum|_count|_max)?$");

    @Test
    void queriesOnlyMetricsTollgatePublishes() throws Exception {
        JsonNode dashboard = JsonMapper.builder().build().readTree(Files.readString(DASHBOARD));
        Set<String> published = new HashSet<>();
        for (String name : List.of(
                GatewayMetrics.REQUESTS,
                GatewayMetrics.TOKENS,
                GatewayMetrics.COST,
                GatewayMetrics.DURATION,
                GatewayMetrics.FIRST_EVENT,
                GatewayMetrics.BUDGET_SPENT,
                GatewayMetrics.BUDGET_AVAILABLE,
                GatewayMetrics.IN_FLIGHT,
                Gateway.LIMITS_REFUSED_METRIC,
                Gateway.PROVIDERS_BUSY_METRIC,
                Gateway.FALLBACKS_METRIC)) {
            published.add(name.replace('.', '_'));
        }

        Set<String> queried = new HashSet<>();
        for (JsonNode panel : dashboard.get("panels")) {
            for (JsonNode target : panel.path("targets")) {
                Matcher metric = METRIC.matcher(target.get("expr").asString());
                while (metric.find()) {
                    queried.add(SUFFIX.matcher(metric.group(1)).replaceFirst(""));
                }
            }
        }

        assertThat(queried).hasSize(published.size()).isSubsetOf(published);
    }
}
