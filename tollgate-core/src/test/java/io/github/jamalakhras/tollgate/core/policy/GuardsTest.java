package io.github.jamalakhras.tollgate.core.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.redact.Detector;
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

    private static final RequestFeaturesGuard STRICT = new RequestFeaturesGuard(Policy.NONE);

    private static GuardRequest using(RequestFeatures features, @Nullable String dataClass) {
        return new GuardRequest("support-bot", "qwen3", "ollama", "local", dataClass, 1, features);
    }

    @Test
    void namesTheDetectorsAPolicyAppliesToADataClass() {
        Policy policy = new Policy(
                List.of(),
                Map.of(),
                Map.of(Detector.EMAIL, List.of("internal"), Detector.IP, List.of("internal", "public")),
                Map.of(Detector.SECRET, List.of("public")));

        assertThat(policy.redacts("internal")).containsExactlyInAnyOrder(Detector.EMAIL, Detector.IP);
        assertThat(policy.redacts("public")).containsExactly(Detector.IP);
        assertThat(policy.refuses("public")).containsExactly(Detector.SECRET);
        assertThat(policy.refuses("internal")).isEmpty();
        assertThat(policy.redacts(null)).isEmpty();
        assertThat(Policy.NONE.redacts("internal")).isEmpty();
    }

    @Test
    void requestFeaturesRefusesWhatIsNeverAllowedFirst() {
        var features = new RequestFeatures(
                List.of("The field 'mcp_servers' is not allowed", "second"),
                List.of("The field 'x' is not one Tollgate has reviewed"),
                Set.of(Feature.REMOTE_IMAGES));

        assertThat(STRICT.check(using(features, "internal")))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'mcp_servers' is not allowed"));
        assertThat(STRICT.type()).isEqualTo("request-features");
    }

    @Test
    void requestFeaturesRefusesUnreviewedFieldsBeforeFeatures() {
        var features = new RequestFeatures(
                List.of(), List.of("The field 'x' is not one Tollgate has reviewed"), Set.of(Feature.REMOTE_IMAGES));

        assertThat(STRICT.check(using(features, "internal")))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'x' is not one Tollgate has reviewed"));
    }

    @Test
    void requestFeaturesNamesTheFirstFeatureAndTheDataClass() {
        var features = new RequestFeatures(List.of(), List.of(), Set.of(Feature.PROVIDER_FILES, Feature.REMOTE_IMAGES));

        assertThat(STRICT.check(using(features, "internal")))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "This request uses images by URL, which this policy does not allow for data class 'internal'"));
        assertThat(STRICT.check(using(features, null)))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED, "This request uses images by URL, which this policy does not allow"));
        assertThat(STRICT.check(using(RequestFeatures.NONE, null))).isEqualTo(Decision.ALLOW);
    }

    @Test
    void requestFeaturesAllowsWhatThePolicyAllowsForTheDataClass() {
        var guard = new RequestFeaturesGuard(new Policy(
                List.of(),
                Map.of(
                        Feature.REMOTE_IMAGES,
                        List.of("public", "internal"),
                        Feature.UNREVIEWED_FIELDS,
                        List.of("internal"))));
        var images = new RequestFeatures(List.of(), List.of(), Set.of(Feature.REMOTE_IMAGES));
        var unreviewed =
                new RequestFeatures(List.of(), List.of("The field 'x' is not one Tollgate has reviewed"), Set.of());

        assertThat(guard.check(using(images, "internal"))).isEqualTo(Decision.ALLOW);
        assertThat(guard.check(using(images, "confidential")))
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "This request uses images by URL, which this policy does not allow for data class"
                                + " 'confidential'"));
        assertThat(guard.check(using(images, null))).isInstanceOf(Decision.Deny.class);
        assertThat(guard.check(using(unreviewed, "internal"))).isEqualTo(Decision.ALLOW);
        assertThat(guard.check(using(unreviewed, "public"))).isInstanceOf(Decision.Deny.class);
    }

    @Test
    void requestFeaturesNeverAllowsWhatIsRefused() {
        var permissive =
                new RequestFeaturesGuard(new Policy(List.of(), Map.of(Feature.UNREVIEWED_FIELDS, List.of("internal"))));
        var features = new RequestFeatures(List.of("The field 'mcp_servers' is not allowed"), List.of(), Set.of());

        assertThat(permissive.check(using(features, "internal")))
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'mcp_servers' is not allowed"));
    }

    @Test
    void namesFeaturesAsTheConfigDoes() {
        assertThat(Feature.fromConfigName("unreviewed-fields")).isEqualTo(Feature.UNREVIEWED_FIELDS);
        assertThat(Feature.REMOTE_DOCUMENTS.configName()).isEqualTo("remote-documents");
        assertThatThrownBy(() -> Feature.fromConfigName("web-fetch"))
                .hasMessage("feature must be one of remote-images, remote-documents, provider-files, web-search,"
                        + " unreviewed-fields, was web-fetch");
    }

    @Test
    void policyCopiesItsFeatures() {
        List<String> classes = new ArrayList<>(List.of("public"));
        var policy = new Policy(List.of(), Map.of(Feature.REMOTE_IMAGES, classes));
        classes.add("internal");

        assertThat(policy.allows(Feature.REMOTE_IMAGES, "internal")).isFalse();
        assertThat(policy.allows(Feature.REMOTE_IMAGES, "public")).isTrue();
        assertThat(policy.allows(Feature.PROVIDER_FILES, "public")).isFalse();
        assertThat(policy.features()).isUnmodifiable();
        assertThat(policy.features().get(Feature.REMOTE_IMAGES)).isUnmodifiable();
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
    void aMonitoredGuardRecordsItsRefusalWithoutStoppingTheRequest() {
        var policy = new Policy(List.of(new MonitoredGuard(new RequestSizeGuard(10)), new RequestSizeGuard(100)));

        Policy.Evaluation passed = policy.evaluate(request("local", "public", 50));
        Policy.Evaluation refused = policy.evaluate(request("local", "public", 500));

        assertThat(passed.decision()).isEqualTo(Decision.ALLOW);
        assertThat(passed.steps()).extracting(GuardStep::monitored).containsExactly(true, false);
        assertThat(passed.steps().getFirst().decision()).isInstanceOf(Decision.Deny.class);
        assertThat(refused.decision()).isInstanceOf(Decision.Deny.class);
        assertThat(refused.steps()).hasSize(2);
        assertThat(new MonitoredGuard(new RequestSizeGuard(10)).type()).isEqualTo("request-size");
        assertThat(MonitoredGuard.underlying(new MonitoredGuard(new RequestSizeGuard(10))))
                .isEqualTo(new RequestSizeGuard(10));
        assertThat(MonitoredGuard.underlying(new RequestSizeGuard(10))).isEqualTo(new RequestSizeGuard(10));
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
