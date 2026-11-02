package io.github.jamalakhras.tollgate.server.cli;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.Names;
import io.github.jamalakhras.tollgate.server.audit.AuditDirectory;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.List;

public final class Commands {

    static final int FAILED = 1;
    static final int USAGE_ERROR = 2;

    private static final String USAGE = """
            Usage:
              java -jar tollgate.jar                                            Start the gateway
              java -jar tollgate.jar keys create --tenant <name>                Create an API key for a tenant
              java -jar tollgate.jar audit keys create                          Create the audit signing and HMAC keys
              java -jar tollgate.jar audit verify --public-key <key> <directory> Verify an audit trail
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final SecureRandom random;

    public Commands(PrintStream out, PrintStream err, SecureRandom random) {
        this.out = out;
        this.err = err;
        this.random = random;
    }

    public static boolean isCommand(String[] args) {
        return args.length > 0 && !args[0].startsWith("-");
    }

    public int run(List<String> args) {
        if (args.size() == 4 && args.subList(0, 3).equals(List.of("keys", "create", "--tenant"))) {
            return createKey(args.get(3));
        }
        if (args.equals(List.of("audit", "keys", "create"))) {
            return createAuditKeys();
        }
        if (args.size() == 5 && args.subList(0, 3).equals(List.of("audit", "verify", "--public-key"))) {
            return verify(args.get(3), Path.of(args.get(4)));
        }
        err.print(USAGE);
        return USAGE_ERROR;
    }

    private int createKey(String tenant) {
        try {
            Names.check("tenant", tenant);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return USAGE_ERROR;
        }
        ApiKey key = ApiKey.generate(random);
        out.printf("""
                New key for tenant %s. It is shown only once:

                    %s

                Add this entry under keys: in tollgate.yaml:

                    - { id: %s, tenant: %s, sha256: %s }

                Then reload the configuration from the admin port:

                    curl -X POST -H "Authorization: Bearer $TOLLGATE_ADMIN_TOKEN" \\
                        http://127.0.0.1:8081/admin/v1/config/reload
                """, tenant, key.value(), key.id(), tenant, key.sha256());
        return 0;
    }

    private int createAuditKeys() {
        AuditKeys.NewKeys keys = AuditKeys.generate(random);
        out.printf("""
                New audit keys. The two secrets are shown only once.

                Give Tollgate the secrets as environment variables, from your secret store:

                    TOLLGATE_AUDIT_SIGNING_KEY=%s
                    TOLLGATE_AUDIT_HMAC_KEY=%s

                Add this section to tollgate.yaml. Give the public key to anyone who verifies the trail:

                    audit:
                      mode: sync
                      directory: audit
                      publicKey: %s
                      signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                      hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """, keys.signingKey(), keys.hmacKey(), keys.publicKey());
        return 0;
    }

    private int verify(String publicKeyText, Path directory) {
        PublicKey publicKey;
        try {
            publicKey = AuditKeys.publicKey(publicKeyText);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return USAGE_ERROR;
        }
        if (!Files.isDirectory(directory)) {
            err.println("No audit trail at " + directory.toAbsolutePath());
            return FAILED;
        }
        TrailVerifier.Report report;
        try {
            report = AuditDirectory.verify(directory, publicKey);
        } catch (IOException e) {
            err.println("Could not read the audit trail in " + directory + ": " + e.getMessage());
            return FAILED;
        }
        report.warnings().forEach(warning -> out.println("Warning: " + warning));
        report.failures().forEach(failure -> out.println("Failed: " + failure));
        out.printf(
                "Checked %s in %s.%n",
                count(report.records(), "record", "records"),
                count(report.batches(), "sealed batch", "sealed batches"));
        Seal last = report.lastSeal();
        if (last != null) {
            out.printf("The last batch is %d, sealed at %s with root %s.%n", last.batch(), last.time(), last.root());
        }
        if (!report.intact()) {
            out.printf(
                    "The audit trail FAILED verification with %s.%n",
                    count(report.failures().size(), "problem", "problems"));
            return FAILED;
        }
        out.println("The audit trail is intact.");
        return 0;
    }

    private static String count(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
