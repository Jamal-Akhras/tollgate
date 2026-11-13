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
        Limits limits,
        @Nullable String userHeader) {

    public Tenant {
        models = List.copyOf(models);
        if (new HashSet<>(models).size() != models.size()) {
            throw new IllegalArgumentException("models must not repeat a model");
        }
        Objects.requireNonNull(limits, "limits");
        if (userHeader != null && !userHeader.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("userHeader must be a header name such as X-OpenWebUI-User-Id");
        }
    }

    public Tenant(List<String> models, @Nullable String dataClass, @Nullable String policy, @Nullable Budget budget) {
        this(models, dataClass, policy, budget, false, Limits.NONE, null);
    }

    public boolean mayUse(String model) {
        return models.contains(model);
    }
}
