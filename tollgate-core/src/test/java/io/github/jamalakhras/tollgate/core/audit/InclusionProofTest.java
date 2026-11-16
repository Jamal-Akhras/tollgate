package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class InclusionProofTest {

    private static final String HASH = "0".repeat(64);
    private static final String OTHER = "f".repeat(64);

    @Test
    void provesTheFifthOfSevenRecordsAgainstPinnedValues() {
        InclusionProof proof = InclusionProof.of(batch(7), 5);

        assertThat(proof.index()).isEqualTo(5);
        assertThat(proof.size()).isEqualTo(7);
        assertThat(proof.encoding())
                .isEqualTo(
                        "AQEAAAAJcmVxdWVzdC01AAAAAGromB4ehIAAAAAAC3N1cHBvcnQtYm90AAAADGs3UW0yeFBhNGJacgAAAAlhbnRocm9waWMAAAANY2xhdWRlLXNvbm5ldAAAAAlhbnRocm9waWMAAAARY2xhdWRlLXNvbm5ldC00LTUBAAAACGludGVybmFsPxwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAmrKpGxEREREREREREREREREREREREREREREREREREREREQAAAAIAAAAMcmVxdWVzdC1zaXplAAAAAAAAAAg0AAAACmRhdGEtY2xhc3MAAAAAAAAADUgAAQAAAANVU0QAAAAACRPp4A==");
        assertThat(proof.leafHash()).isEqualTo("e2c803413dd1a9c780824d310c709f28c7c320d9b7d025c6b61c536549d98b8b");
        assertThat(proof.path())
                .containsExactly(
                        "cf3ddaa573d32b552d74ad0023e1e2ccd1fbc8f8895be3bade414e8c4996ffaa",
                        "83218bbb8c80af13da576e5b5ed7848bc0e6edcd8814076838704c0096b6f572",
                        "95583037b5e3ccf1034b20f31d62d5ee6fd70f16c24a9997d5abd12e2c143392");
        assertThat(proof.root()).isEqualTo("087488b9fe80236f80e48b029a1b678a3b9c86956a0566a0f474ca6e996f23f7");
        assertThat(proof.verifies()).isTrue();
    }

    @Property(tries = 300)
    void everyPositionInEveryBatchProves(
            @ForAll @IntRange(min = 1, max = 300) int size, @ForAll @IntRange(min = 0, max = 299) int position) {
        assertThat(InclusionProof.of(batch(size), position % size).verifies()).isTrue();
    }

    @Property(tries = 300)
    void doesNotProveAtAnotherPosition(
            @ForAll @IntRange(min = 2, max = 300) int size,
            @ForAll @IntRange(min = 0, max = 299) int position,
            @ForAll @IntRange(min = 1, max = 299) int shift) {
        InclusionProof proof = InclusionProof.of(batch(size), position % size);
        int moved = (proof.index() + shift) % size;
        Assume.that(moved != proof.index());

        assertThat(new InclusionProof(
                                moved, proof.size(), proof.encoding(), proof.leafHash(), proof.path(), proof.root())
                        .verifies())
                .isFalse();
    }

    @Property(tries = 300)
    void doesNotProveWithAChangedPathEntry(
            @ForAll @IntRange(min = 2, max = 300) int size,
            @ForAll @IntRange(min = 0, max = 299) int position,
            @ForAll @IntRange(min = 0, max = 20) int entry) {
        InclusionProof proof = InclusionProof.of(batch(size), position % size);
        List<String> path = new ArrayList<>(proof.path());
        path.set(entry % path.size(), OTHER);

        assertThat(new InclusionProof(
                                proof.index(), proof.size(), proof.encoding(), proof.leafHash(), path, proof.root())
                        .verifies())
                .isFalse();
    }

    @Property(tries = 300)
    void doesNotProveWithADroppedPathEntry(
            @ForAll @IntRange(min = 2, max = 300) int size, @ForAll @IntRange(min = 0, max = 299) int position) {
        InclusionProof proof = InclusionProof.of(batch(size), position % size);

        assertThat(new InclusionProof(
                                proof.index(),
                                proof.size(),
                                proof.encoding(),
                                proof.leafHash(),
                                proof.path().subList(1, proof.path().size()),
                                proof.root())
                        .verifies())
                .isFalse();
    }

    @Property(tries = 300)
    void doesNotProveWithAnAddedPathEntry(
            @ForAll @IntRange(min = 1, max = 300) int size, @ForAll @IntRange(min = 0, max = 299) int position) {
        InclusionProof proof = InclusionProof.of(batch(size), position % size);
        List<String> path = new ArrayList<>(proof.path());
        path.add(OTHER);

        assertThat(new InclusionProof(
                                proof.index(), proof.size(), proof.encoding(), proof.leafHash(), path, proof.root())
                        .verifies())
                .isFalse();
    }

    @Property(tries = 300)
    void doesNotProveWithAChangedLeafHashRootOrEncoding(
            @ForAll @IntRange(min = 1, max = 300) int size, @ForAll @IntRange(min = 0, max = 299) int position) {
        InclusionProof proof = InclusionProof.of(batch(size), position % size);
        byte[] bytes = Base64.getDecoder().decode(proof.encoding());
        byte[] longer = Arrays.copyOf(bytes, bytes.length + 1);
        String changed = Base64.getEncoder().encodeToString(longer);

        assertThat(new InclusionProof(proof.index(), proof.size(), proof.encoding(), OTHER, proof.path(), proof.root())
                        .verifies())
                .isFalse();
        assertThat(new InclusionProof(
                                proof.index(), proof.size(), proof.encoding(), proof.leafHash(), proof.path(), OTHER)
                        .verifies())
                .isFalse();
        assertThat(new InclusionProof(
                                proof.index(), proof.size(), changed, proof.leafHash(), proof.path(), proof.root())
                        .verifies())
                .isFalse();
    }

    @Test
    void listsTheLeafHashOfEachRecordInOrder() {
        List<AuditRecord> batch = batch(3);

        assertThat(InclusionProof.leafHashes(batch))
                .containsExactly(
                        InclusionProof.of(batch, 0).leafHash(),
                        InclusionProof.of(batch, 1).leafHash(),
                        InclusionProof.of(batch, 2).leafHash());
    }

    @Test
    void agreesWithTheSealOfItsBatch() {
        List<AuditRecord> batch = batch(7);

        assertThat(InclusionProof.of(batch, 3).root())
                .isEqualTo(((Seal) Trails.sealed(batch, 7).getLast()).root());
    }

    @Test
    void rejectsAPositionOutsideTheBatch() {
        assertThatThrownBy(() -> InclusionProof.of(batch(3), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InclusionProof.of(batch(3), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsASizeUnderOne() {
        assertThatThrownBy(() -> new InclusionProof(0, 0, "AA==", HASH, List.of(), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be at least 1");
    }

    @Test
    void rejectsAnIndexOutsideTheSize() {
        assertThatThrownBy(() -> new InclusionProof(2, 2, "AA==", HASH, List.of(), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("index must be from 0 to 1");
        assertThatThrownBy(() -> new InclusionProof(-1, 2, "AA==", HASH, List.of(), HASH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEncodingThatIsNotBase64() {
        assertThatThrownBy(() -> new InclusionProof(0, 1, "not base64!", HASH, List.of(), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("encoding must be base64");
    }

    @Test
    void rejectsALeafHashThatIsNotSha256Hex() {
        assertThatThrownBy(() -> new InclusionProof(0, 1, "AA==", "AB", List.of(), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leafHash must be 64 lowercase hex characters");
    }

    @Test
    void rejectsARootThatIsNotSha256Hex() {
        assertThatThrownBy(() -> new InclusionProof(0, 1, "AA==", HASH, List.of(), "AB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("root must be 64 lowercase hex characters");
    }

    @Test
    void rejectsAPathEntryThatIsNotSha256Hex() {
        assertThatThrownBy(() -> new InclusionProof(0, 2, "AA==", HASH, List.of("AB"), HASH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("path must be 64 lowercase hex characters");
    }

    @Test
    void copiesThePath() {
        List<String> path = new ArrayList<>(List.of(HASH));
        InclusionProof proof = new InclusionProof(0, 2, "AA==", HASH, path, HASH);

        path.add(OTHER);

        assertThat(proof.path()).containsExactly(HASH);
    }

    private static List<AuditRecord> batch(int size) {
        return IntStream.range(0, size)
                .<AuditRecord>mapToObj(i -> Records.intent("request-" + i))
                .toList();
    }
}
