package io.github.jamalakhras.tollgate.core.policy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public record RequestFeatures(List<String> refused, List<String> unreviewed, Set<Feature> used, List<String> tools) {

    public static final RequestFeatures NONE = new RequestFeatures(List.of(), List.of(), Set.of(), List.of());

    public RequestFeatures {
        refused = List.copyOf(refused);
        unreviewed = List.copyOf(unreviewed);
        tools = List.copyOf(tools);
        used = used.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(used));
    }
}
