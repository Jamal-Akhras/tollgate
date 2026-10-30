package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Guard;
import io.github.jamalakhras.tollgate.core.policy.GuardRequest;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
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
        for (var tenant : tenants.entrySet()) {
            checkTenant(tenant.getKey(), tenant.getValue(), dataClasses, models, policies);
        }
        for (var policy : policies.entrySet()) {
            for (Guard guard : policy.getValue().guards()) {
                if (guard instanceof DataClassGuard dataClassGuard) {
                    checkDataClassGuard(policy.getKey(), dataClassGuard, dataClasses);
                }
            }
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
        Provider provider = providers.get(target.provider());
        if (provider == null) {
            throw new IllegalStateException("Model " + model + " has no provider");
        }
        return Optional.of(new Route(model, target, target.provider(), provider));
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

    public Admission admit(String tenant, Route route, @Nullable String requestedDataClass, long sizeBytes) {
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
                tenant, route.model(), route.providerName(), route.provider().region(), dataClass, sizeBytes);
        Policy.Evaluation evaluation = policyOf(caller).evaluate(request);
        return new Admission(dataClass, evaluation.decision(), evaluation.steps());
    }

    private Policy policyOf(Tenant tenant) {
        String name = tenant.policy() == null ? DEFAULT_POLICY : tenant.policy();
        return policies.getOrDefault(name, Policy.NONE);
    }

    private static Admission refused(@Nullable String dataClass, Refusal refusal, String message) {
        return new Admission(dataClass, Decision.deny(refusal, message), List.of());
    }

    private static void checkTenant(
            String name,
            Tenant tenant,
            List<String> dataClasses,
            Map<String, Model> models,
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
        if (tenant.budget() != null) {
            for (String model : tenant.models()) {
                Model definition = models.get(model);
                if (definition != null && definition.maxOutputTokens() == null) {
                    throw new IllegalArgumentException("tenants." + name + ".budget needs models." + model
                            + ".maxOutputTokens, so that the most a call can cost is known");
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
