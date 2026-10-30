package io.github.jamalakhras.tollgate.core.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Trails {

    private Trails() {}

    static List<AuditRecord> requests(int allowed) {
        List<AuditRecord> records = new ArrayList<>();
        for (int i = 0; i < allowed; i++) {
            records.add(Records.intent("request-" + i));
            if (i % 3 == 1) {
                records.add(Records.refused("refused-" + i));
            }
            records.add(Records.outcome("request-" + i));
        }
        return records;
    }

    static List<AuditEntry> sealed(List<AuditRecord> records, int batchSize) {
        return sealed(records, batchSize, new Sealer(TestKeys.KEYS, Optional.empty()));
    }

    static List<AuditEntry> sealed(List<AuditRecord> records, int batchSize, Sealer sealer) {
        List<AuditEntry> entries = new ArrayList<>();
        for (AuditRecord record : records) {
            entries.add(record);
            sealer.add(record);
            if (sealer.unsealed() == batchSize) {
                entries.add(sealer.seal(Records.TIME));
            }
        }
        if (sealer.unsealed() > 0) {
            entries.add(sealer.seal(Records.TIME));
        }
        return entries;
    }

    @SafeVarargs
    static TrailVerifier.Report verify(List<AuditEntry>... files) {
        TrailVerifier verifier = new TrailVerifier(TestKeys.PUBLIC_KEY);
        for (int file = 0; file < files.length; file++) {
            verifier.startFile("trail-" + file + ".jsonl");
            for (int line = 0; line < files[file].size(); line++) {
                verifier.entry(line + 1, files[file].get(line));
            }
        }
        return verifier.finish();
    }

    static List<String> messages(List<TrailVerifier.Problem> problems) {
        return problems.stream().map(TrailVerifier.Problem::message).toList();
    }
}
