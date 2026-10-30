package io.github.jamalakhras.tollgate.core.audit;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface AuditSink extends AutoCloseable {

    Optional<Seal> lastSeal() throws IOException;

    void write(List<AuditEntry> entries) throws IOException;

    @Override
    void close() throws IOException;
}
