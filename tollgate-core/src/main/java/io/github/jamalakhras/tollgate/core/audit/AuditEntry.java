package io.github.jamalakhras.tollgate.core.audit;

public sealed interface AuditEntry permits AuditRecord, Seal {}
