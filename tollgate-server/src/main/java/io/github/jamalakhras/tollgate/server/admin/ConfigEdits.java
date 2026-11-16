package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.ConfigOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class ConfigEdits {

    private ConfigEdits() {}

    static ConfigOperation guardMode(String policy, int index, @Nullable String mode) {
        if (!"enforce".equals(mode) && !"monitor".equals(mode)) {
            throw new BadEdit("mode must be enforce or monitor, was " + mode);
        }
        return new ConfigOperation("guard-mode", "policies." + policy + ".guards[" + index + "]", document -> {
            Map<String, Object> root = new LinkedHashMap<>(document);
            Map<String, Object> policies = mapOf(root.get("policies"));
            if (!policies.containsKey(policy)) {
                throw new NotFound("No policy named '" + policy + "'");
            }
            Map<String, Object> entry = mapOf(policies.get(policy));
            List<Object> guards = listOf(entry.get("guards"));
            if (index < 0 || index >= guards.size()) {
                throw new NotFound("Policy '" + policy + "' has no guard " + index);
            }
            Map<String, Object> guard = mapOf(guards.get(index));
            if ("monitor".equals(mode)) {
                guard.put("mode", "monitor");
            } else {
                guard.remove("mode");
            }
            guards.set(index, guard);
            entry.put("guards", guards);
            policies.put(policy, entry);
            root.put("policies", policies);
            return root;
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(@Nullable Object value) {
        return value instanceof List<?> list ? new ArrayList<>((List<Object>) list) : new ArrayList<>();
    }

    static final class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    static final class BadEdit extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BadEdit(String message) {
            super(message);
        }
    }
}
