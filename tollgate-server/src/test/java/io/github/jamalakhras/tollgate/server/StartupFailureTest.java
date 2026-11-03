package io.github.jamalakhras.tollgate.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.server.audit.JsonLinesAuditSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class StartupFailureTest {

    @TempDir
    Path directory;

    @Test
    void explainsAMissingConfigWithoutAStackTrace(CapturedOutput output) {
        Path missing = directory.resolve("missing.yaml");

        assertThatThrownBy(() -> start("tollgate.config=" + missing))
                .hasStackTraceContaining(StartupException.class.getName());

        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("No Tollgate configuration at " + missing)
                .doesNotContain("Application run failed");
    }

    @Test
    void saysWhyTheAuditTrailCannotBeOpened(CapturedOutput output) throws Exception {
        AuditKeys.NewKeys keys = AuditKeys.generate(new SecureRandom());
        Path config = Files.writeString(directory.resolve("tollgate.yaml"), """
                tenants: {}
                audit:
                  mode: sync
                  directory: audit
                  publicKey: %s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(keys.publicKey()));
        Path audit = directory.resolve("audit");

        try (JsonLinesAuditSink _ = JsonLinesAuditSink.open(audit)) {
            assertThatThrownBy(() -> start(
                            "tollgate.config=" + config,
                            "TOLLGATE_AUDIT_SIGNING_KEY=" + keys.signingKey(),
                            "TOLLGATE_AUDIT_HMAC_KEY=" + keys.hmacKey()))
                    .hasStackTraceContaining(StartupException.class.getName());
        }

        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("Could not open the audit trail in " + audit
                        + ": Another Tollgate is already writing to the audit directory " + audit);
    }

    private static void start(String... properties) {
        String[] args = Stream.of(properties).map(property -> "--" + property).toArray(String[]::new);
        new SpringApplicationBuilder(TollgateServerApplication.class)
                .properties("server.port=0", "management.server.port=0", "tollgate.admin.port=" + TestPorts.free())
                .run(args)
                .close();
    }
}
