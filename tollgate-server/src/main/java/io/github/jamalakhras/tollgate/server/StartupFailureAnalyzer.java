package io.github.jamalakhras.tollgate.server;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

class StartupFailureAnalyzer extends AbstractFailureAnalyzer<StartupException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupException cause) {
        return new FailureAnalysis(String.valueOf(cause.getMessage()), null, cause);
    }
}
