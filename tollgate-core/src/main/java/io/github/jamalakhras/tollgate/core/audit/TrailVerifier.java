package io.github.jamalakhras.tollgate.core.audit;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class TrailVerifier {

    static final String GENESIS = "0".repeat(64);

    private final PublicKey publicKey;
    private final String keyId;
    private final List<Problem> failures = new ArrayList<>();
    private final List<Problem> warnings = new ArrayList<>();
    private final List<byte[]> unsealed = new ArrayList<>();
    private final Set<String> intents = new HashSet<>();
    private final Map<String, Problem> awaitingOutcome = new LinkedHashMap<>();

    private String file = "";
    private long firstUnsealedLine;
    private long records;
    private long batches;
    private String previousRoot = GENESIS;
    private long previousBatch;
    private @Nullable Seal lastSeal;

    public TrailVerifier(PublicKey publicKey) {
        this.publicKey = publicKey;
        this.keyId = AuditKeys.keyId(publicKey);
    }

    public void startFile(String name) {
        endFile();
        file = name;
    }

    public void entry(long line, AuditEntry entry) {
        switch (entry) {
            case Intent intent -> intent(line, intent);
            case Outcome outcome -> outcome(line, outcome);
            case Seal seal -> seal(line, seal);
        }
    }

    public void unreadable(long line, String problem) {
        failures.add(new Problem(file, line, problem));
    }

    public void tornLine(long line) {
        warnings.add(new Problem(file, line, "The last line is incomplete, as a crash during a write leaves it"));
    }

    public Report finish() {
        endFile();
        warnings.addAll(awaitingOutcome.values());
        return new Report(failures, warnings, records, batches, lastSeal);
    }

    private void intent(long line, Intent intent) {
        addLeaf(line, intent);
        if (!intents.add(intent.id())) {
            failures.add(new Problem(file, line, "Request " + intent.id() + " has more than one intent"));
        } else if (intent.allowed()) {
            awaitingOutcome.put(
                    intent.id(), new Problem(file, line, "Request " + intent.id() + " was allowed but has no outcome"));
        }
    }

    private void outcome(long line, Outcome outcome) {
        addLeaf(line, outcome);
        if (awaitingOutcome.remove(outcome.id()) == null) {
            failures.add(new Problem(
                    file, line, "Outcome for request " + outcome.id() + " does not follow an allowed intent"));
        }
    }

    private void addLeaf(long line, AuditRecord record) {
        if (unsealed.isEmpty()) {
            firstUnsealedLine = line;
        }
        unsealed.add(MerkleTree.leafHash(Encoding.of(record)));
        records++;
    }

    private void seal(long line, Seal seal) {
        String name = "Batch " + seal.batch();
        if (seal.batch() != previousBatch + 1) {
            failures.add(new Problem(file, line, name + " follows batch " + previousBatch));
        }
        if (seal.records() != unsealed.size()) {
            failures.add(new Problem(
                    file,
                    line,
                    name + " counts " + seal.records() + " records, but " + unsealed.size() + " precede it"));
        } else if (!seal.root().equals(Digests.HEX.formatHex(MerkleTree.root(unsealed)))) {
            failures.add(new Problem(file, line, name + " does not match the records it seals"));
        }
        if (!seal.previous().equals(previousRoot)) {
            failures.add(new Problem(file, line, name + " does not link to the batch before it"));
        }
        if (!seal.key().equals(keyId)) {
            failures.add(new Problem(file, line, name + " is signed by key " + seal.key() + ", not " + keyId));
        } else if (!AuditKeys.verify(publicKey, Encoding.of(seal), seal.signature())) {
            failures.add(new Problem(file, line, name + " has an invalid signature"));
        }
        unsealed.clear();
        previousBatch = seal.batch();
        previousRoot = seal.root();
        lastSeal = seal;
        batches++;
    }

    private void endFile() {
        if (!unsealed.isEmpty()) {
            warnings.add(new Problem(
                    file,
                    firstUnsealedLine,
                    unsealed.size() + " records from here to the end of the file are not sealed"));
            unsealed.clear();
        }
    }

    public record Problem(String file, long line, String message) {

        @Override
        public String toString() {
            return file + ":" + line + ": " + message;
        }
    }

    public record Report(
            List<Problem> failures,
            List<Problem> warnings,
            long records,
            long batches,
            @Nullable Seal lastSeal) {

        public Report {
            failures = List.copyOf(failures);
            warnings = List.copyOf(warnings);
        }

        public boolean intact() {
            return failures.isEmpty();
        }
    }
}
