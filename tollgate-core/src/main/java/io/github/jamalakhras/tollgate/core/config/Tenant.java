package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.limit.Limits;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Tenant(
        List<String> models,
        @Nullable String dataClass,
        @Nullable String policy,
        @Nullable Budget budget,
        boolean showCosts,
        Limits limits) {

    public Tenant {
        models = List.copyOf(models);
        if (new HashSet<>(models).size() != models.size()) {
            throw new IllegalArgumentException("models must not repeat a model");
        }
        Objects.requireNonNull(limits, "limits");
    }

    public Tenant(List<String> models, @Nullable String dataClass, @Nullable String policy, @Nullable Budget budget) {
        this(models, dataClass, policy, budget, false, Limits.NONE);
    }

    public boolean mayUse(String model) {
        return models.contains(model);
    }
}
