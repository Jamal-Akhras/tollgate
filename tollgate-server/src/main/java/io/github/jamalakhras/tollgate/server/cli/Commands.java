package io.github.jamalakhras.tollgate.server.cli;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.Model;
import io.github.jamalakhras.tollgate.core.config.Names;
import io.github.jamalakhras.tollgate.core.money.ContextTier;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.PriceCatalog;
import io.github.jamalakhras.tollgate.core.money.TokenPrice;
import io.github.jamalakhras.tollgate.core.money.TokenRates;
import io.github.jamalakhras.tollgate.server.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.server.gateway.ConfigFile;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Commands {

    static final int FAILED = 1;
    static final int USAGE_ERROR = 2;

    private static final String USAGE = """
            Usage:
              java -jar tollgate.jar                                            Start the gateway
              java -jar tollgate.jar init [directory]                           Create a working config and its secrets
              java -jar tollgate.jar keys create --tenant <name>                Create an API key for a tenant
              java -jar tollgate.jar audit keys create                          Create the audit signing and HMAC keys
              java -jar tollgate.jar audit verify --public-key <key> <directory> Verify an audit trail
              java -jar tollgate.jar prices show <provider> <model>             Show a model's price in the catalog
              java -jar tollgate.jar prices pin <tollgate.yaml>                 Print the catalog prices a config uses
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
        if (!args.isEmpty() && args.getFirst().equals("init") && args.size() <= 2) {
            return init(Path.of(args.size() == 2 ? args.get(1) : "").toAbsolutePath());
        }
        if (args.size() == 4 && args.subList(0, 3).equals(List.of("keys", "create", "--tenant"))) {
            return createKey(args.get(3));
        }
        if (args.equals(List.of("audit", "keys", "create"))) {
            return createAuditKeys();
        }
        if (args.size() == 5 && args.subList(0, 3).equals(List.of("audit", "verify", "--public-key"))) {
            return verify(args.get(3), Path.of(args.get(4)));
        }
        if (args.size() == 4 && args.subList(0, 2).equals(List.of("prices", "show"))) {
            return showPrice(args.get(2), args.get(3));
        }
        if (args.size() == 3 && args.subList(0, 2).equals(List.of("prices", "pin"))) {
            return pinPrices(Path.of(args.get(2)));
        }
        err.print(USAGE);
        return USAGE_ERROR;
    }

    private int init(Path directory) {
        Init.Created created;
        try {
            created = Init.create(directory, random);
        } catch (FileAlreadyExistsException e) {
            err.println(e.getFile() + " already exists. Tollgate init never overwrites a config or its secrets.");
            return FAILED;
        } catch (IOException e) {
            err.println("Could not create the config in " + directory + ": " + e.getMessage());
            return FAILED;
        }
        out.printf(
                """
                Created %s and %s.

                %s holds the audit keys and the admin token, and only you can read it.
                Keep it out of version control, and back it up: without the keys, new audit
                records cannot be signed.

                The API key for tenant %s. It is shown only once:

                    %s

                The config sends requests to Ollama at localhost:11434 with qwen3:8b. Change
                providers and models to match yours, then start Tollgate with the secrets in
                its environment:

                    set -a; . %s; set +a
                    java -jar tollgate.jar

                With systemd, set EnvironmentFile= to %s. With Docker, use --env-file.
                """,
                created.config(),
                created.secrets(),
                Init.SECRETS,
                Init.TENANT,
                created.key().value(),
                created.secrets(),
                created.secrets());
        return 0;
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

    private int showPrice(String provider, String model) {
        PriceCatalog catalog = PriceCatalog.bundled();
        if (!catalog.covers(provider)) {
            err.println("The price catalog has no provider " + provider);
            return FAILED;
        }
        Optional<ModelPrice> price = catalog.price(provider, model);
        if (price.isEmpty()) {
            err.println("The " + provider + " price catalog has no model " + model);
            return FAILED;
        }
        out.printf("%s on %s, in USD per million tokens, from %s:%n%n", model, provider, catalog.version());
        price(price.get(), "    ").forEach(out::println);
        return 0;
    }

    private int pinPrices(Path config) {
        GatewayConfig read;
        try {
            read = ConfigFile.read(config).config();
        } catch (IllegalStateException | UncheckedIOException e) {
            err.println(e.getMessage());
            return FAILED;
        }
        List<Map.Entry<String, Model>> fromCatalog = read.models().entrySet().stream()
                .filter(model -> model.getValue().priceCatalog() != null)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (fromCatalog.isEmpty()) {
            out.println("No model in " + config + " takes its price from the catalog.");
            return 0;
        }
        out.printf(
                "These models take their price from %s. To pin a price, put its lines under the model in %s:%n%n",
                PriceCatalog.bundled().version(), config);
        out.println("    models:");
        for (Map.Entry<String, Model> model : fromCatalog) {
            out.println("      " + model.getKey() + ":");
            price(model.getValue().price(), "        ").forEach(out::println);
        }
        return 0;
    }

    private static List<String> price(ModelPrice price, String indent) {
        List<String> lines = new ArrayList<>();
        lines.add(indent + "price:");
        rates(price.rates()).forEach(rate -> lines.add(indent + "  " + rate));
        if (!price.longContext().isEmpty()) {
            lines.add(indent + "  longContext:");
            for (ContextTier tier : price.longContext()) {
                lines.add(indent + "    - { fromTokens: " + tier.fromTokens() + ", "
                        + String.join(", ", rates(tier.rates())) + " }");
            }
        }
        return lines;
    }

    private static List<String> rates(TokenRates rates) {
        List<String> written = new ArrayList<>();
        written.add("input: " + amount(rates.input()));
        written.add("output: " + amount(rates.output()));
        if (!rates.reasoning().equals(rates.output())) {
            written.add("reasoning: " + amount(rates.reasoning()));
        }
        written.add("cacheRead: " + amount(rates.cacheRead()));
        written.add("cacheWrite: " + amount(rates.cacheWrite()));
        return written;
    }

    private static String amount(TokenPrice price) {
        return price.perMillionTokens().toBigDecimal().stripTrailingZeros().toPlainString();
    }

    private static String count(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }
}
