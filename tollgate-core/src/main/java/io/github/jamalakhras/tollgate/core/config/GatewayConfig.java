package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.Guard;
import io.github.jamalakhras.tollgate.core.policy.GuardRequest;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import io.github.jamalakhras.tollgate.core.policy.RequestFeaturesGuard;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record GatewayConfig(
        List<String> dataClasses,
        Map<String, Provider> providers,
        Map<String, Model> models,
        Map<String, Tenant> tenants,
        List<TenantKey> keys,
        Map<String, Policy> policies,
        AuditSettings audit) {

    public static final String DEFAULT_POLICY = "default";

    public GatewayConfig {
        dataClasses = List.copyOf(dataClasses);
        providers = Map.copyOf(providers);
        models = Map.copyOf(models);
        tenants = Map.copyOf(tenants);
        keys = List.copyOf(keys);
        policies = Map.copyOf(policies);
        Objects.requireNonNull(audit, "audit");

        dataClasses.forEach(name -> Names.check("data class", name));
        providers.keySet().forEach(name -> Names.check("provider", name));
        models.keySet().forEach(name -> Names.check("model", name));
        tenants.keySet().forEach(name -> Names.check("tenant", name));
        policies.keySet().forEach(name -> Names.check("policy", name));

        if (new HashSet<>(dataClasses).size() != dataClasses.size()) {
            throw new IllegalArgumentException("dataClasses must not repeat a data class");
        }
        for (var model : models.entrySet()) {
            if (!providers.containsKey(model.getValue().provider())) {
                throw new IllegalArgumentException("models." + model.getKey() + ".provider: no provider named '"
                        + model.getValue().provider() + "'");
            }
        }
        for (var model : models.entrySet()) {
            checkFallback(model.getKey(), model.getValue(), models, providers);
        }
        for (var tenant : tenants.entrySet()) {
            checkTenant(tenant.getKey(), tenant.getValue(), dataClasses, models, providers, policies);
        }
        for (var policy : policies.entrySet()) {
            for (Guard guard : policy.getValue().guards()) {
                if (guard instanceof DataClassGuard dataClassGuard) {
                    checkDataClassGuard(policy.getKey(), dataClassGuard, dataClasses);
                }
            }
            checkFeatures(policy.getKey(), policy.getValue(), dataClasses);
            checkDetectors(policy.getKey(), policy.getValue(), dataClasses);
        }
        Set<String> keyIds = new HashSet<>();
        for (TenantKey key : keys) {
            if (!tenants.containsKey(key.tenant())) {
                throw new IllegalArgumentException(
                        "keys: key " + key.id() + " belongs to an unknown tenant '" + key.tenant() + "'");
            }
            if (!keyIds.add(key.id())) {
                throw new IllegalArgumentException("keys: key ID " + key.id() + " appears more than once");
            }
        }
    }

    public Optional<Route> route(String tenant, String model) {
        Tenant caller = tenants.get(tenant);
        Model target = models.get(model);
        if (caller == null || target == null || !caller.mayUse(model)) {
            return Optional.empty();
        }
        return Optional.of(routeTo(model, target));
    }

    private Route routeTo(String model, @Nullable Model target) {
        Provider provider = target == null ? null : providers.get(target.provider());
        if (target == null || provider == null) {
            throw new IllegalStateException("Model " + model + " has no provider");
        }
        return new Route(model, target, target.provider(), provider);
    }

    public List<Route> fallbacks(Route route) {
        return route.definition().fallback().stream()
                .map(model -> routeTo(model, models.get(model)))
                .toList();
    }

    public List<Route> routes(String tenant) {
        Tenant caller = tenants.get(tenant);
        if (caller == null) {
            return List.of();
        }
        return caller.models().stream()
                .map(model -> route(tenant, model).orElseThrow())
                .toList();
    }

    public Admission admit(
            String tenant, Route route, @Nullable String requestedDataClass, long sizeBytes, RequestFeatures features) {
        Tenant caller = tenants.get(tenant);
        if (caller == null) {
            throw new IllegalStateException("Unknown tenant " + tenant);
        }
        String dataClass = caller.dataClass();
        if (requestedDataClass != null) {
            int requested = dataClasses.indexOf(requestedDataClass);
            if (requested < 0) {
                return refused(
                        dataClass,
                        Refusal.INVALID_DATA_CLASS,
                        "Unknown data class '" + requestedDataClass + "'; this gateway defines " + dataClasses);
            }
            if (dataClass != null && requested < dataClasses.indexOf(dataClass)) {
                return refused(
                        dataClass,
                        Refusal.POLICY_DENIED,
                        "Data class '" + requestedDataClass + "' is less strict than this tenant's '" + dataClass
                                + "'");
            }
            dataClass = requestedDataClass;
        }
        var request = new GuardRequest(
                tenant, route.model(), route.providerName(), route.provider().region(), dataClass, sizeBytes, features);
        Policy policy = policyOf(caller);
        List<Guard> guards = new ArrayList<>();
        guards.add(new RequestFeaturesGuard(policy));
        guards.addAll(policy.guards());
        Policy.Evaluation evaluation = new Policy(guards).evaluate(request);
        return new Admission(
                dataClass,
                evaluation.decision(),
                evaluation.steps(),
                policy.refuses(dataClass),
                policy.redacts(dataClass));
    }

    private Policy policyOf(Tenant tenant) {
        String name = tenant.policy() == null ? DEFAULT_POLICY : tenant.policy();
        return policies.getOrDefault(name, Policy.NONE);
    }

    private static Admission refused(@Nullable String dataClass, Refusal refusal, String message) {
        return new Admission(dataClass, Decision.deny(refusal, message), List.of(), Set.of(), Set.of());
    }

    private static void checkTenant(
            String name,
            Tenant tenant,
            List<String> dataClasses,
            Map<String, Model> models,
            Map<String, Provider> providers,
            Map<String, Policy> policies) {
        for (String model : tenant.models()) {
            if (!models.containsKey(model)) {
                throw new IllegalArgumentException("tenants." + name + ".models: no model named '" + model + "'");
            }
        }
        String dataClass = tenant.dataClass();
        if (dataClass == null && !dataClasses.isEmpty()) {
            throw new IllegalArgumentException(
                    "tenants." + name + ".dataClass is required because dataClasses are defined");
        }
        if (dataClass != null && !dataClasses.contains(dataClass)) {
            throw new IllegalArgumentException(
                    "tenants." + name + ".dataClass: no data class named '" + dataClass + "'");
        }
        String policy = tenant.policy();
        if (policy != null && !policies.containsKey(policy)) {
            throw new IllegalArgumentException("tenants." + name + ".policy: no policy named '" + policy + "'");
        }
        if (dataClass != null) {
            String applied = policy == null ? DEFAULT_POLICY : policy;
            Policy enforcing = policies.get(applied);
            if (enforcing == null) {
                throw new IllegalArgumentException(
                        "tenants." + name + " has no policy to enforce its data class; set tenants." + name
                                + ".policy or define a '" + DEFAULT_POLICY + "' policy");
            }
            if (enforcing.guards().stream().noneMatch(DataClassGuard.class::isInstance)) {
                throw new IllegalArgumentException("tenants." + name + ": policy '" + applied
                        + "' has no data-class guard, so data class '" + dataClass + "' would not be enforced");
            }
        }
        List<String> served = new ArrayList<>(tenant.models());
        for (String model : tenant.models()) {
            Model definition = models.get(model);
            if (definition != null) {
                definition.fallback().stream()
                        .filter(fallback -> !served.contains(fallback))
                        .forEach(served::add);
            }
        }
        if (tenant.budget() != null) {
            for (String model : served) {
                Model definition = models.get(model);
                if (definition != null && definition.maxOutputTokens() == null) {
                    throw new IllegalArgumentException("tenants." + name + ".budget needs models." + model
                            + ".maxOutputTokens, so that the most a call can cost is known");
                }
            }
        }
        Policy applied = policies.get(policy == null ? DEFAULT_POLICY : policy);
        if (applied != null
                && !applied.features()
                        .getOrDefault(Feature.WEB_SEARCH, List.of())
                        .isEmpty()) {
            for (String model : served) {
                Model definition = models.get(model);
                if (definition == null) {
                    continue;
                }
                Provider provider = providers.get(definition.provider());
                if (provider != null && provider.format() == WireFormat.ANTHROPIC && !definition.offersWebSearch()) {
                    throw new IllegalArgumentException("tenants." + name + " may use web search, so models." + model
                            + " needs price.webSearch and maxWebSearches");
                }
            }
        }
    }

    private static void checkFallback(
            String name, Model model, Map<String, Model> models, Map<String, Provider> providers) {
        Set<String> named = new HashSet<>();
        for (String fallback : model.fallback()) {
            String path = "models." + name + ".fallback";
            Model target = models.get(fallback);
            if (target == null) {
                throw new IllegalArgumentException(path + ": no model named '" + fallback + "'");
            }
            if (fallback.equals(name)) {
                throw new IllegalArgumentException(path + " must not name the model itself");
            }
            if (!named.add(fallback)) {
                throw new IllegalArgumentException(path + " names '" + fallback + "' more than once");
            }
            Provider own = providers.get(model.provider());
            Provider other = providers.get(target.provider());
            if (own != null && other != null && own.format() != other.format()) {
                throw new IllegalArgumentException(path + ": '" + fallback + "' uses the "
                        + other.format().configName() + " format and '" + name + "' the "
                        + own.format().configName() + " format, and Tollgate does not translate between them");
            }
        }
    }

    private static void checkFeatures(String name, Policy policy, List<String> dataClasses) {
        policy.features().forEach((feature, allowed) -> {
            for (String dataClass : allowed) {
                if (!dataClasses.contains(dataClass)) {
                    throw new IllegalArgumentException("policies." + name + ".features." + feature.configName()
                            + ": no data class named '" + dataClass + "'");
                }
            }
        });
    }

    private static void checkDetectors(String name, Policy policy, List<String> dataClasses) {
        for (var setting :
                Map.of("redact", policy.redact(), "refuse", policy.refuse()).entrySet()) {
            setting.getValue().forEach((detector, chosen) -> {
                for (String dataClass : chosen) {
                    if (!dataClasses.contains(dataClass)) {
                        throw new IllegalArgumentException("policies." + name + "." + setting.getKey() + "."
                                + detector.configName() + ": no data class named '" + dataClass + "'");
                    }
                }
            });
        }
        for (String dataClass : dataClasses) {
            for (Detector detector : policy.redacts(dataClass)) {
                if (policy.refuses(dataClass).contains(detector)) {
                    throw new IllegalArgumentException("policies." + name + ": " + detector.configName()
                            + " is both redacted and refused for data class '" + dataClass + "'; choose one");
                }
            }
        }
    }

    private static void checkDataClassGuard(String policy, DataClassGuard guard, List<String> dataClasses) {
        for (String dataClass : guard.regions().keySet()) {
            if (!dataClasses.contains(dataClass)) {
                throw new IllegalArgumentException("policies." + policy + ": the " + DataClassGuard.TYPE
                        + " guard names an unknown data class '" + dataClass + "'");
            }
        }
    }

    public record Route(String model, Model definition, String providerName, Provider provider) {

        public String upstream() {
            return definition.upstream();
        }
    }
}
