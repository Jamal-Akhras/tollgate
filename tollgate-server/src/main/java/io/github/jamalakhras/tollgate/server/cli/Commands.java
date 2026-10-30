package io.github.jamalakhras.tollgate.server.cli;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.Names;
import java.io.PrintStream;
import java.util.List;
import java.util.random.RandomGenerator;

public final class Commands {

    static final int USAGE_ERROR = 2;

    private static final String USAGE = """
            Usage:
              java -jar tollgate.jar                              Start the gateway
              java -jar tollgate.jar keys create --tenant <name>  Create an API key for a tenant
            """;

    private final PrintStream out;
    private final PrintStream err;
    private final RandomGenerator random;

    public Commands(PrintStream out, PrintStream err, RandomGenerator random) {
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

                Add this entry under keys: in tollgate.yaml, then restart Tollgate:

                    - { id: %s, tenant: %s, sha256: %s }
                """, tenant, key.value(), key.id(), tenant, key.sha256());
        return 0;
    }
}
