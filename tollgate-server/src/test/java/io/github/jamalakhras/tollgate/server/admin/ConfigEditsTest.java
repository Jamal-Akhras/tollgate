package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class ConfigEditsTest {

    private static Map<String, Object> document() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "request-size");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("type", "data-class");
        second.put("mode", "monitor");
        Map<String, Object> third = new LinkedHashMap<>();
        third.put("type", "model-allowlist");
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("guards", new ArrayList<>(List.of(first, second, third)));
        Map<String, Object> policies = new LinkedHashMap<>();
        policies.put("default", policy);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenants", new LinkedHashMap<>());
        root.put("policies", policies);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> guards(Map<String, Object> document) {
        Map<String, Object> policies = (Map<String, Object>) Objects.requireNonNull(document.get("policies"));
        Map<String, Object> policy = (Map<String, Object>) Objects.requireNonNull(policies.get("default"));
        return (List<Map<String, Object>>) Objects.requireNonNull(policy.get("guards"));
    }

    @Test
    void addsTheModeKeyWhenMonitoring() {
        Map<String, Object> changed =
                ConfigEdits.guardMode("default", 0, "monitor").change().apply(document());

        assertThat(guards(changed).get(0)).containsEntry("mode", "monitor");
        assertThat(guards(changed).get(1)).isEqualTo(guards(document()).get(1));
        assertThat(guards(changed).get(2)).isEqualTo(guards(document()).get(2));
    }

    @Test
    void removesTheModeKeyWhenEnforcing() {
        Map<String, Object> changed =
                ConfigEdits.guardMode("default", 1, "enforce").change().apply(document());

        assertThat(guards(changed).get(1)).containsOnlyKeys("type");
    }

    @Test
    void keepsTheOrderOfTheGuards() {
        Map<String, Object> changed =
                ConfigEdits.guardMode("default", 1, "enforce").change().apply(document());

        assertThat(guards(changed))
                .extracting(guard -> guard.get("type"))
                .containsExactly("request-size", "data-class", "model-allowlist");
    }

    @Test
    void leavesTheDocumentItWasGiven() {
        Map<String, Object> original = document();

        Map<String, Object> changed =
                ConfigEdits.guardMode("default", 0, "monitor").change().apply(original);

        assertThat(changed).isNotSameAs(original);
        assertThat(original).isEqualTo(document());
    }

    @Test
    void namesTheOperationAndThePlaceItTouches() {
        var operation = ConfigEdits.guardMode("default", 2, "monitor");

        assertThat(operation.name()).isEqualTo("guard-mode");
        assertThat(operation.path()).isEqualTo("policies.default.guards[2]");
    }

    @Test
    void doesNotFindAMissingPolicyOrGuard() {
        assertThatThrownBy(() ->
                        ConfigEdits.guardMode("other", 0, "monitor").change().apply(document()))
                .isInstanceOf(ConfigEdits.NotFound.class)
                .hasMessage("No policy named 'other'");
        assertThatThrownBy(() ->
                        ConfigEdits.guardMode("default", -1, "monitor").change().apply(document()))
                .isInstanceOf(ConfigEdits.NotFound.class)
                .hasMessage("Policy 'default' has no guard -1");
        assertThatThrownBy(() ->
                        ConfigEdits.guardMode("default", 3, "monitor").change().apply(document()))
                .isInstanceOf(ConfigEdits.NotFound.class)
                .hasMessage("Policy 'default' has no guard 3");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tenant(Map<String, Object> document, String name) {
        return (Map<String, Object>) Objects.requireNonNull(
                ((Map<String, Object>) Objects.requireNonNull(document.get("tenants"))).get(name));
    }

    private static Map<String, Object> withTenants() {
        Map<String, Object> bare = new LinkedHashMap<>();
        bare.put("dataClass", "internal");
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("dataClass", "public");
        existing.put("budget", new LinkedHashMap<>(Map.of("limit", 50, "period", "monthly")));
        Map<String, Object> tenants = new LinkedHashMap<>();
        tenants.put("bare", bare);
        tenants.put("existing", existing);
        tenants.put("empty", null);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenants", tenants);
        return root;
    }

    @Test
    void setsABudgetWhereThereWasNone() {
        Map<String, Object> changed =
                ConfigEdits.budget("bare", "12.50", "daily").change().apply(withTenants());

        assertThat(tenant(changed, "bare"))
                .containsEntry("dataClass", "internal")
                .containsEntry("budget", Map.of("limit", new BigDecimal("12.50"), "period", "daily"));
    }

    @Test
    void replacesABudgetAndKeepsTheTenantsOtherKeys() {
        Map<String, Object> changed =
                ConfigEdits.budget("existing", "20", "total").change().apply(withTenants());

        assertThat(tenant(changed, "existing"))
                .containsEntry("dataClass", "public")
                .containsEntry("budget", Map.of("limit", new BigDecimal("20"), "period", "total"));
    }

    @Test
    void turnsAnEmptyTenantIntoAMap() {
        Map<String, Object> changed =
                ConfigEdits.budget("empty", "1", "monthly").change().apply(withTenants());

        assertThat(tenant(changed, "empty")).containsOnlyKeys("budget");
    }

    @Test
    void doesNotFindAMissingTenant() {
        assertThatThrownBy(() ->
                        ConfigEdits.budget("nobody", "1", "monthly").change().apply(withTenants()))
                .isInstanceOf(ConfigEdits.NotFound.class)
                .hasMessage("No tenant named 'nobody'");
    }

    @Test
    void refusesALimitOrPeriodItCannotUse() {
        assertThatThrownBy(() -> ConfigEdits.budget("bare", "abc", "daily"))
                .isInstanceOf(ConfigEdits.BadEdit.class)
                .hasMessage("limit must be a number that is not negative, was abc");
        assertThatThrownBy(() -> ConfigEdits.budget("bare", "-1", "daily"))
                .isInstanceOf(ConfigEdits.BadEdit.class)
                .hasMessage("limit must be a number that is not negative, was -1");
        assertThatThrownBy(() -> ConfigEdits.budget("bare", "1", "weekly"))
                .isInstanceOf(ConfigEdits.BadEdit.class)
                .hasMessageContaining("daily")
                .hasMessageContaining("was weekly");
    }

    private static Map<String, Object> withKeys() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenants", new LinkedHashMap<>(Map.of("bare", new LinkedHashMap<>())));
        root.put(
                "keys",
                new ArrayList<>(List.of(
                        new LinkedHashMap<>(Map.of("id", "one", "tenant", "bare", "sha256", "aa")),
                        new LinkedHashMap<>(Map.of("id", "two", "tenant", "bare", "sha256", "bb")))));
        return root;
    }

    private static List<String> keyIds(Map<String, Object> document) {
        return ((List<?>) Objects.requireNonNull(document.get("keys")))
                .stream()
                        .map(key -> String.valueOf(((Map<?, ?>) key).get("id")))
                        .toList();
    }

    @Test
    void appendsAKeyAndKeepsTheOnesThatWereThere() {
        Map<String, Object> changed =
                ConfigEdits.addKey("bare", "three", "cc").change().apply(withKeys());

        assertThat(keyIds(changed)).containsExactly("one", "two", "three");
        assertThat(((List<?>) Objects.requireNonNull(changed.get("keys"))).get(2))
                .isEqualTo(Map.of("id", "three", "tenant", "bare", "sha256", "cc"));
    }

    @Test
    void makesTheKeyListWhenThereIsNone() {
        Map<String, Object> document = withKeys();
        document.remove("keys");

        Map<String, Object> changed =
                ConfigEdits.addKey("bare", "one", "aa").change().apply(document);

        assertThat(keyIds(changed)).containsExactly("one");
    }

    @Test
    void doesNotAddAKeyToAMissingTenant() {
        assertThatThrownBy(() ->
                        ConfigEdits.addKey("nobody", "three", "cc").change().apply(withKeys()))
                .isInstanceOf(ConfigEdits.NotFound.class)
                .hasMessage("No tenant named 'nobody'");
    }

    @Test
    void namesTheKeyOperations() {
        assertThat(ConfigEdits.addKey("bare", "x", "cc").name()).isEqualTo("key-create");
        assertThat(ConfigEdits.addKey("bare", "x", "cc").path()).isEqualTo("keys[x]");
    }

    @Test
    void refusesAModeItDoesNotKnow() {
        assertThatThrownBy(() -> ConfigEdits.guardMode("default", 0, "off"))
                .isInstanceOf(ConfigEdits.BadEdit.class)
                .hasMessage("mode must be enforce or monitor, was off");
    }
}
