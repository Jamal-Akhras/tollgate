package io.github.jamalakhras.tollgate.core.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ApiKey(String id, String secret) {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int ID_LENGTH = 12;
    private static final int SECRET_LENGTH = 43;
    private static final Pattern FORMAT =
            Pattern.compile("tg_([0-9A-Za-z]{" + ID_LENGTH + "})_([0-9A-Za-z]{" + SECRET_LENGTH + "})");

    public ApiKey {
        if (!FORMAT.matcher(format(id, secret)).matches()) {
            throw new IllegalArgumentException("Not a Tollgate key");
        }
    }

    public static ApiKey generate(RandomGenerator random) {
        return new ApiKey(randomText(random, ID_LENGTH), randomText(random, SECRET_LENGTH));
    }

    public static Optional<ApiKey> parse(String value) {
        Matcher matcher = FORMAT.matcher(value);
        return matcher.matches() ? Optional.of(new ApiKey(matcher.group(1), matcher.group(2))) : Optional.empty();
    }

    public String value() {
        return format(id, secret);
    }

    public byte[] digest() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }

    public String sha256() {
        return HexFormat.of().formatHex(digest());
    }

    @Override
    public String toString() {
        return "tg_" + id + "_…";
    }

    private static String format(String id, String secret) {
        return "tg_" + id + "_" + secret;
    }

    private static String randomText(RandomGenerator random, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return text.toString();
    }
}
