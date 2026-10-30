package io.github.jamalakhras.tollgate.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MerkleTreeTest {

    private static final List<byte[]> RFC_6962_LEAVES = List.of(
            new byte[0],
            new byte[] {0x00},
            new byte[] {0x10},
            new byte[] {0x20, 0x21},
            new byte[] {0x30, 0x31},
            new byte[] {0x40, 0x41, 0x42, 0x43},
            bytes(0x50, 8),
            bytes(0x60, 16));

    @ParameterizedTest
    @CsvSource({
        "0, e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "1, 6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d",
        "2, fac54203e7cc696cf0dfcb42c92a1d9dbaf70ad9e621f4bd8d98662f00e3c125",
        "3, aeb6bcfe274b70a14fb067a5e5578264db0fa9b51af5e0ba159158f329e06e77",
        "4, d37ee418976dd95753c1c73862b9398fa2a2cf9b4ff0fdfe8b30cd95209614b7",
        "5, 4e3bbb1f7b478dcfe71fb631631519a3bca12c9aefca1612bfce4c13a86264d4",
        "6, 76e67dadbcdf1e10e1b74ddc608abd2f98dfb16fbce75277b5232a127f2087ef",
        "7, ddb89be403809e325750d3d263cd78929c2942b7942a34b77e122c9594a74c8c",
        "8, 5dc9da79a70659a9ad559cb701ded9a2ab9d823aad2f4960cfe370eff4604328"
    })
    void matchesTheRfc6962TestVectors(int size, String root) {
        List<byte[]> leafHashes = RFC_6962_LEAVES.subList(0, size).stream()
                .map(MerkleTree::leafHash)
                .toList();

        assertThat(Digests.HEX.formatHex(MerkleTree.root(leafHashes))).isEqualTo(root);
    }

    @Property
    void changesWhenAnyLeafChanges(
            @ForAll @IntRange(min = 1, max = 300) int size, @ForAll @IntRange(min = 0, max = 299) int changed) {
        List<byte[]> leafHashes = new ArrayList<>(IntStream.range(0, size)
                .mapToObj(i -> MerkleTree.leafHash(new byte[] {(byte) i, (byte) (i >> 8)}))
                .toList());
        byte[] root = MerkleTree.root(leafHashes);

        leafHashes.set(changed % size, MerkleTree.leafHash(new byte[] {-1, -1, -1}));

        assertThat(MerkleTree.root(leafHashes)).isNotEqualTo(root);
    }

    private static byte[] bytes(int first, int count) {
        byte[] bytes = new byte[count];
        for (int i = 0; i < count; i++) {
            bytes[i] = (byte) (first + i);
        }
        return bytes;
    }
}
