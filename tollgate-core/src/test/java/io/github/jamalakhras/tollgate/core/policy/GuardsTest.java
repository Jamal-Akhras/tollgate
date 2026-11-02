package io.github.jamalakhras.tollgate.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GuardsTest {

    private static GuardRequest request(@Nullable String region, @Nullable String dataClass, long size) {
        return new GuardRequest("support-bot", "qwen3", "ollama", region, dataClass, size, RequestFeatures.NONE);
    }

    private static GuardRequest using(RequestFeatures features, @Nullable String dataClass) {
        return new GuardRequest("support-bot", "qwen3", "ollama", "local", dataClass, 1, features);
    }

    @Test
    void requestFeaturesRefusesWhatIsNeverAllowedFirst() {
        var features = new RequestFeatures(
                List.of("The field 'mcp_servers' is not allowed", "second"),
                List.of("The field 'x' is not one Tollgate has reviewed"),
                Set.of(Feature.REMOTE_IMAGES));

        assertThat(RequestFeaturesGuard.INSTANCE.check(using(features, "internal")))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'mcp_servers' is not allowed"));
        assertThat(RequestFeaturesGuard.INSTANCE.type()).isEqualTo("request-features");
    }

    @Test
    void requestFeaturesRefusesUnreviewedFieldsBeforeFeatures() {
        var features = new RequestFeatures(
                List.of(), List.of("The field 'x' is not one Tollgate has reviewed"), Set.of(Feature.REMOTE_IMAGES));

        assertThat(RequestFeaturesGuard.INSTANCE.check(using(features, "internal")))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'x' is not one Tollgate has reviewed"));
    }

    @Test
    void requestFeaturesNamesTheFirstFeatureAndTheDataClass() {
        var features = new RequestFeatures(List.of(), List.of(), Set.of(Feature.PROVIDER_FILES, Feature.REMOTE_IMAGES));

        assertThat(RequestFeaturesGuard.INSTANCE.check(using(features, "internal")))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "This request uses images by URL, which this policy does not allow for data class 'internal'"));
        assertThat(RequestFeaturesGuard.INSTANCE.check(using(features, null)))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED, "This request uses images by URL, which this policy does not allow"));
        assertThat(RequestFeaturesGuard.INSTANCE.check(using(RequestFeatures.NONE, null)))
                .isEqualTo(Decision.ALLOW);
    }

    @Test
    void requestFeaturesAreCopied() {
        List<String> refused = new ArrayList<>(List.of("a"));
        Set<Feature> used = new HashSet<>(Set.of(Feature.REMOTE_DOCUMENTS));
        var features = new RequestFeatures(refused, List.of(), used);
        refused.add("b");
        used.add(Feature.PROVIDER_FILES);

        assertThat(features.refused()).containsExactly("a").isUnmodifiable();
        assertThat(features.used()).containsExactly(Feature.REMOTE_DOCUMENTS).isUnmodifiable();
    }

    @Test
    void requestSizeAllowsUpToTheLimit() {
        var guard = new RequestSizeGuard(100);

        assertThat(guard.check(request(null, null, 100))).isEqualTo(Decision.ALLOW);
        assertThat(guard.check(request(null, null, 101)))
                .isEqualTo(Decision.deny(Refusal.TOO_LARGE, "The request is 101 bytes; this policy allows 100"));
        assertThat(guard.type()).isEqualTo("request-size");
        assertThatThrownBy(() -> new RequestSizeGuard(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RequestSizeGuard(1).maxBytes()).isOne();
    }

    @Test
    void dataClassAllowsOnlyListedRegions() {
        var guard = new DataClassGuard(Map.of("public", List.of("us", "local"), "confidential", List.of("local")));

        assertThat(guard.check(request("us", "public", 1))).isEqualTo(Decision.ALLOW);
        assertThat(guard.check(request("local", "confidential", 1))).isEqualTo(Decision.ALLOW);
        assertThat(guard.check(request("us", "confidential", 1)))
                .isEqualTo(
                        Decision.deny(
                                Refusal.POLICY_DENIED,
                                "Data class 'confidential' may go only to regions [local]; the model 'qwen3' is in region 'us'"));
        assertThat(guard.type()).isEqualTo("data-class");
    }

    @Test
    void dataClassFailsClosed() {
        var guard = new DataClassGuard(Map.of("public", List.of("us")));

        assertThat(guard.check(request(null, "public", 1)))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "Data class 'public' may go only to regions [us]; the model 'qwen3' has no region"));
        assertThat(guard.check(request("us", "internal", 1))).isInstanceOf(Decision.Deny.class);
        assertThat(guard.check(request("us", null, 1)))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The request has no data class"));
    }

    @Test
    void dataClassGuardCopiesItsRegions() {
        List<String> regions = new ArrayList<>(List.of("us"));
        var guard = new DataClassGuard(Map.of("public", regions));
        regions.add("eu");

        assertThat(guard.check(request("eu", "public", 1))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void policyRecordsEveryGuardItRan() {
        var policy = new Policy(List.of(new RequestSizeGuard(100), new RequestSizeGuard(50)));

        Policy.Evaluation passed = policy.evaluate(request(null, null, 10));
        Policy.Evaluation stopped = policy.evaluate(request(null, null, 200));

        assertThat(passed.decision()).isEqualTo(Decision.ALLOW);
        assertThat(passed.steps()).hasSize(2).allSatisfy(step -> {
            assertThat(step.guard()).isEqualTo("request-size");
            assertThat(step.duration()).isBetween(Duration.ZERO, Duration.ofSeconds(10));
        });
        assertThat(stopped.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(stopped.steps()).hasSize(1);
        assertThat(Policy.NONE.evaluate(request(null, null, 10)).steps()).isEmpty();
    }
}
