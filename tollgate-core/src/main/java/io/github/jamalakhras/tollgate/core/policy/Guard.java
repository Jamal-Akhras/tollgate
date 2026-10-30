package io.github.jamalakhras.tollgate.core.policy;

public interface Guard {

    String type();

    Decision check(GuardRequest request);
}
