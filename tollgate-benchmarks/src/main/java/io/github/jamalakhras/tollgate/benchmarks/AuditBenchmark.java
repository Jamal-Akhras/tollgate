package io.github.jamalakhras.tollgate.benchmarks;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditSink;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Threads(8)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class AuditBenchmark {

    private static final String HASH = "3f1c" + "0".repeat(60);
    private static final Money COST = new Money(1_000_000, Currency.getInstance("USD"));

    @Param({"memory", "disk"})
    String sink = "memory";

    private AuditLog log;
    private Path directory;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        AuditKeys.NewKeys keys = AuditKeys.generate(new SecureRandom());
        directory = Files.createTempDirectory("tollgate-audit-bench");
        AuditSink target = sink.equals("disk") ? new DiskSink(directory.resolve("audit.log")) : new MemorySink();
        log = AuditLog.start(
                target, AuditKeys.of(keys.publicKey(), keys.signingKey(), keys.hmacKey()), Clock.systemUTC(), true);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        log.close();
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }

    @Benchmark
    public void writerCapacity() {
        String id = UUID.randomUUID().toString();
        Clock clock = Clock.systemUTC();
        log.append(new Intent(
                id,
                clock.instant(),
                Configs.TENANT,
                "k7Qm2xPa4bZr",
                WireFormat.OPENAI,
                Configs.MODEL,
                "stub",
                Configs.MODEL,
                "internal",
                HASH,
                log.hmac(id.getBytes(StandardCharsets.UTF_8)),
                List.of(new GuardStep("data-class", Decision.ALLOW, Duration.ofNanos(1_500))),
                Decision.ALLOW,
                COST));
        log.append(new Outcome(
                id,
                clock.instant(),
                Outcome.Result.RESPONDED,
                200,
                new Usage(512, 200, 0, 0),
                COST,
                Duration.ofMillis(1),
                HASH));
    }

    private static final class MemorySink implements AuditSink {

        @Override
        public Optional<Seal> lastSeal() {
            return Optional.empty();
        }

        @Override
        public void write(List<AuditEntry> entries) {}

        @Override
        public void close() {}
    }

    private static final class DiskSink implements AuditSink {

        private final FileChannel channel;

        DiskSink(Path file) throws IOException {
            channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }

        @Override
        public Optional<Seal> lastSeal() {
            return Optional.empty();
        }

        @Override
        public void write(List<AuditEntry> entries) throws IOException {
            StringBuilder lines = new StringBuilder();
            entries.forEach(entry -> lines.append(entry).append('\n'));
            channel.write(ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
