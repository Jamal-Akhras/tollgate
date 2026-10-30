package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import java.util.HashSet;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record Tenant(
        List<String> models,
        @Nullable String dataClass,
        @Nullable String policy,
        @Nullable Budget budget) {

    public Tenant {
        models = List.copyOf(models);
        if (new HashSet<>(models).size() != models.size()) {
            throw new IllegalArgumentException("models must not repeat a model");
        }
    }

    public boolean mayUse(String model) {
        return models.contains(model);
    }
}
