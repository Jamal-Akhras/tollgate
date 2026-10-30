package io.github.jamalakhras.tollgate.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AuditKeys {

    static final int HMAC_KEY_BYTES = 32;

    private static final String ED25519 = "Ed25519";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final byte[] PAIRING_PROBE = "tollgate audit key check".getBytes(StandardCharsets.US_ASCII);

    private final PrivateKey signingKey;
    private final String keyId;
    private final SecretKeySpec hmacKey;
    private final String hmacKeyId;

    private AuditKeys(PrivateKey signingKey, String keyId, SecretKeySpec hmacKey, String hmacKeyId) {
        this.signingKey = signingKey;
        this.keyId = keyId;
        this.hmacKey = hmacKey;
        this.hmacKeyId = hmacKeyId;
    }

    public static AuditKeys of(String publicKey, String signingKey, String hmacKey) {
        PublicKey verifyingKey = publicKey(publicKey);
        PrivateKey privateKey = privateKey(signingKey);
        if (!verify(verifyingKey, PAIRING_PROBE, sign(privateKey, PAIRING_PROBE))) {
            throw new IllegalArgumentException("signingKey is not the private half of publicKey");
        }
        byte[] hmacBytes = decode("hmacKey", hmacKey);
        if (hmacBytes.length < HMAC_KEY_BYTES) {
            throw new IllegalArgumentException("hmacKey must be at least " + HMAC_KEY_BYTES + " bytes");
        }
        return new AuditKeys(
                privateKey, keyId(verifyingKey), new SecretKeySpec(hmacBytes, HMAC_SHA256), Digests.keyId(hmacBytes));
    }

    public static NewKeys generate(SecureRandom random) {
        KeyPair pair;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ED25519);
            generator.initialize(NamedParameterSpec.ED25519, random);
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java runtime provides Ed25519", e);
        }
        byte[] hmacKey = new byte[HMAC_KEY_BYTES];
        random.nextBytes(hmacKey);
        Base64.Encoder base64 = Base64.getEncoder();
        return new NewKeys(
                base64.encodeToString(pair.getPublic().getEncoded()),
                base64.encodeToString(pair.getPrivate().getEncoded()),
                base64.encodeToString(hmacKey));
    }

    public static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance(ED25519).generatePublic(new X509EncodedKeySpec(decode("publicKey", base64)));
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("publicKey is not an Ed25519 public key", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides Ed25519", e);
        }
    }

    public static String keyId(PublicKey key) {
        return Digests.keyId(key.getEncoded());
    }

    public String hmac(byte[] content) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(hmacKey);
            return Digests.HEX.formatHex(mac.doFinal(content));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java runtime provides HMAC-SHA256", e);
        }
    }

    String keyId() {
        return keyId;
    }

    String hmacKeyId() {
        return hmacKeyId;
    }

    String sign(byte[] content) {
        return sign(signingKey, content);
    }

    static boolean verify(PublicKey key, byte[] content, String signature) {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ED25519);
            verifier.initVerify(key);
            verifier.update(content);
            return verifier.verify(signatureBytes);
        } catch (InvalidKeyException | SignatureException e) {
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides Ed25519", e);
        }
    }

    private static String sign(PrivateKey key, byte[] content) {
        try {
            Signature signer = Signature.getInstance(ED25519);
            signer.initSign(key);
            signer.update(content);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign with the audit signing key", e);
        }
    }

    private static PrivateKey privateKey(String base64) {
        try {
            return KeyFactory.getInstance(ED25519)
                    .generatePrivate(new PKCS8EncodedKeySpec(decode("signingKey", base64)));
        } catch (InvalidKeySpecException e) {
            throw new IllegalArgumentException("signingKey is not an Ed25519 private key", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides Ed25519", e);
        }
    }

    private static byte[] decode(String name, String base64) {
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not valid base64", e);
        }
    }

    public record NewKeys(String publicKey, String signingKey, String hmacKey) {

        @Override
        public String toString() {
            return "NewKeys[publicKey=" + publicKey + ", signingKey=…, hmacKey=…]";
        }
    }
}
