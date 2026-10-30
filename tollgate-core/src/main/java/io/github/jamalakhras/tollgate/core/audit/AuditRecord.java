package io.github.jamalakhras.tollgate.core.audit;

public sealed interface AuditRecord extends AuditEntry permits Intent, Outcome {

    String id();
}
