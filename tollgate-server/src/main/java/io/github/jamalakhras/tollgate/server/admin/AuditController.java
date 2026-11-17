package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.InclusionProof;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.audit.TrailVerifier;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.gateway.audit.AuditBatches;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.SealView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@Tag(name = "Audit", description = "The signed audit trail and its sealed batches")
@RequestMapping(path = "/admin/v1/audit", produces = MediaType.APPLICATION_JSON_VALUE)
class AuditController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ID = 64;

    private final Gateway gateway;
    private final OneAtATime longReads;

    AuditController(Gateway gateway, OneAtATime longReads) {
        this.gateway = gateway;
        this.longReads = longReads;
    }

    @GetMapping
    @Operation(
            summary = "Show the state of the audit trail",
            description = "The audit mode, the public key the server verifies with and its ID, the number of trail"
                    + " files and the last sealed batch.")
    @ApiResponse(responseCode = "200", description = "The state of the trail")
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    AuditStatus status() throws IOException {
        AuditSettings.Enabled settings = settings();
        Path directory = directory();
        List<AuditBatches.Batch> last = AuditBatches.recent(directory, 1);
        return new AuditStatus(
                settings.synchronous() ? "sync" : "async",
                settings.publicKey(),
                AuditKeys.keyId(AuditKeys.publicKey(settings.publicKey())),
                AuditDirectory.files(directory).size(),
                last.isEmpty() ? null : LiveViews.sealView(last.getFirst().seal()));
    }

    @GetMapping("/batches")
    @Operation(
            summary = "List the latest sealed batches",
            description = "The most recent sealed batches, newest first, read from the end of the trail.")
    @ApiResponse(responseCode = "200", description = "The batches")
    @ApiResponse(
            responseCode = "400",
            description = "limit is not between 1 and 200",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    List<BatchView> batches(
            @Parameter(description = "How many batches to return, from 1 to 200") @RequestParam(defaultValue = "50")
                    int limit)
            throws IOException {
        Path directory = directory();
        if (limit < 1 || limit > 200) {
            throw new BadQuery("limit must be between 1 and 200, was " + limit);
        }
        return AuditBatches.recent(directory, limit).stream()
                .map(batch -> new BatchView(LiveViews.sealView(batch.seal()), batch.file()))
                .toList();
    }

    @GetMapping("/batches/{batch}")
    @Operation(
            summary = "Show one sealed batch",
            description = "The batch's seal and signature, whether the signature and the root check out, and its"
                    + " records' leaf hashes in order. An old batch is found by reading the trail, one such read at a"
                    + " time.")
    @ApiResponse(responseCode = "200", description = "The batch")
    @ApiResponse(
            responseCode = "404",
            description = "No such batch in the trail",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Another long read of the trail is running; retry after the Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    BatchDetail batch(@Parameter(description = "The batch number") @PathVariable long batch) throws Exception {
        PublicKey publicKey = AuditKeys.publicKey(settings().publicKey());
        Path directory = directory();
        AuditBatches.Batch found = longReads
                .run(() -> AuditBatches.batch(directory, batch))
                .orElseThrow(() -> new NotFound("No batch " + batch + " in the audit trail"));
        Seal seal = found.seal();
        List<String> hashes = InclusionProof.leafHashes(found.records());
        List<LeafView> leaves = new ArrayList<>();
        for (int i = 0; i < found.records().size(); i++) {
            AuditRecord record = found.records().get(i);
            leaves.add(new LeafView(i, type(record), record.id(), time(record), hashes.get(i)));
        }
        return new BatchDetail(
                LiveViews.sealView(seal),
                seal.signature(),
                found.file(),
                seal.signedBy(publicKey),
                seal.seals(found.records()),
                List.copyOf(leaves));
    }

    @GetMapping("/records/{id}")
    @Operation(
            summary = "Prove a request's records",
            description = "The request's intent and outcome as the trail holds them, each with its inclusion proof and"
                    + " its batch's seal, or unsealed while its batch is still open. An old record is found by reading"
                    + " the trail, one such read at a time.")
    @ApiResponse(responseCode = "200", description = "The records")
    @ApiResponse(
            responseCode = "400",
            description = "The ID is longer than 64 characters",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No audit record for the request",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Another long read of the trail is running; retry after the Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    RecordProofs record(
            @Parameter(description = "The request ID, with its attempt suffix for a fallback") @PathVariable String id)
            throws Exception {
        PublicKey publicKey = AuditKeys.publicKey(settings().publicKey());
        Path directory = directory();
        if (id.length() > MAX_ID) {
            throw new BadQuery("id must be at most " + MAX_ID + " characters");
        }
        AuditBatches.Found found = longReads.run(() -> AuditBatches.find(directory, id));
        if (found.intent() == null && found.outcome() == null) {
            throw new NotFound("No audit record for request " + id);
        }
        return new RecordProofs(proved(found.intent(), publicKey), proved(found.outcome(), publicKey));
    }

    @GetMapping("/verification")
    @Operation(
            summary = "Verify the whole audit trail",
            description = "Reads every file and checks every seal with the configured public key. One such read at a"
                    + " time.")
    @ApiResponse(responseCode = "200", description = "The result, intact or not")
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "429",
            description = "Another long read of the trail is running; retry after the Retry-After seconds",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    Verification verification() throws Exception {
        PublicKey publicKey = AuditKeys.publicKey(settings().publicKey());
        Path directory = directory();
        long start = System.nanoTime();
        TrailVerifier.Report report = longReads.run(() -> AuditDirectory.verify(directory, publicKey));
        return new Verification(
                report.intact(),
                report.records(),
                report.batches(),
                problems(report.failures()),
                problems(report.warnings()),
                report.lastSeal() == null ? null : LiveViews.sealView(report.lastSeal()),
                (System.nanoTime() - start) / 1_000_000);
    }

    private static List<ProblemView> problems(List<TrailVerifier.Problem> problems) {
        return problems.stream()
                .map(problem -> new ProblemView(problem.file(), problem.line(), problem.message()))
                .toList();
    }

    private static @Nullable ProvedRecord proved(AuditBatches.@Nullable Located located, PublicKey publicKey) {
        if (located == null) {
            return null;
        }
        JsonNode record = JSON.readTree(AuditJson.write(located.record()));
        AuditBatches.Batch batch = located.batch();
        if (batch == null) {
            return new ProvedRecord(record, false, null, null, null);
        }
        InclusionProof proof = InclusionProof.of(batch.records(), located.index());
        return new ProvedRecord(
                record,
                true,
                new ProofView(
                        proof.index(), proof.size(), proof.encoding(), proof.leafHash(), proof.path(), proof.root()),
                LiveViews.sealView(batch.seal()),
                batch.seal().signedBy(publicKey));
    }

    private AuditSettings.Enabled settings() {
        if (gateway.config().audit() instanceof AuditSettings.Enabled enabled) {
            return enabled;
        }
        throw new AuditOff();
    }

    private Path directory() {
        return gateway.auditDirectory().orElseThrow(AuditOff::new);
    }

    private static String type(AuditRecord record) {
        return record instanceof Intent ? "intent" : "outcome";
    }

    private static Instant time(AuditRecord record) {
        return record instanceof Intent intent ? intent.time() : ((Outcome) record).time();
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> badQuery(BadQuery e) {
        return ResponseEntity.badRequest()
                .body(new ConfigController.AdminError("invalid_request", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> auditOff(AuditOff e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConfigController.AdminError("audit_off", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ConfigController.AdminError("not_found", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> busy(OneAtATime.Busy e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "2")
                .body(new ConfigController.AdminError("audit_busy", String.valueOf(e.getMessage())));
    }

    record AuditStatus(
            String mode,
            String publicKey,
            String keyId,
            int files,
            @Nullable SealView lastSeal) {}

    record BatchView(SealView seal, String file) {}

    record BatchDetail(
            SealView seal,
            String signature,
            String file,
            boolean signatureValid,
            boolean rootMatches,
            List<LeafView> leaves) {}

    record LeafView(int index, String type, String id, Instant time, String leafHash) {}

    record RecordProofs(
            @Nullable ProvedRecord intent, @Nullable ProvedRecord outcome) {}

    record ProvedRecord(
            JsonNode record,
            boolean sealed,
            @Nullable ProofView proof,
            @Nullable SealView seal,
            @Nullable Boolean signatureValid) {}

    record ProofView(int index, int size, String encoding, String leafHash, List<String> path, String root) {}

    record Verification(
            boolean intact,
            long records,
            long batches,
            List<ProblemView> failures,
            List<ProblemView> warnings,
            @Nullable SealView lastSeal,
            long millis) {}

    record ProblemView(String file, long line, String message) {}

    static final class BadQuery extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BadQuery(String message) {
            super(message);
        }
    }

    static final class NotFound extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    static final class AuditOff extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AuditOff() {
            super("The audit trail is off: audit.mode is off");
        }
    }
}
