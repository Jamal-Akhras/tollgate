package io.github.jamalakhras.tollgate.core.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class Digests {

    static final HexFormat HEX = HexFormat.of();

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");

    private Digests() {}

    static byte[] sha256(byte[]... parts) {
        MessageDigest digest = newSha256();
        for (byte[] part : parts) {
            digest.update(part);
        }
        return digest.digest();
    }

    static String keyId(byte[] key) {
        byte[] hash = sha256(key);
        return HEX.formatHex(hash, 0, 8);
    }

    static void requireSha256(String name, String value) {
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hex characters");
        }
    }

    static void requireKeyId(String name, String value) {
        if (!KEY_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 16 lowercase hex characters");
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
