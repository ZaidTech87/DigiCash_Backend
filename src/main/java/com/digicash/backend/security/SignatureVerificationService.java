package com.digicash.backend.security;

import org.springframework.stereotype.Component;

import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/**
 * Server-side counterpart to the Android app's CryptoManager, limited to
 * exactly what a backend needs: decoding a public key the client already
 * sent, computing its fingerprint, and verifying a signature against it.
 *
 * This service NEVER handles a private key - there is no private key
 * anywhere on this backend, by design. It only ever verifies signatures
 * that were already produced on-device using a key that never left that
 * device's Android Keystore.
 *
 * Every algorithm and encoding choice here matches the Android side
 * exactly (see Phase 0 analysis):
 *  - Public key encoding: Base64 of the X.509 (SubjectPublicKeyInfo) form.
 *  - Fingerprint: SHA-256 digest of the X.509-encoded key bytes, rendered
 *    as UPPERCASE, COLON-SEPARATED hex (e.g. "3F:A1:9B:...").
 *  - Signature algorithm: SHA256withRSA.
 */
@Component
public class SignatureVerificationService {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String FINGERPRINT_SEPARATOR = ":";

    /**
     * Decodes a Base64-encoded X.509 RSA public key string, exactly as
     * produced by Android's PublicKeyInfo.getBase64PublicKey().
     *
     * @throws InvalidPublicKeyException if the value is not valid Base64
     *         or does not represent a valid X.509 RSA public key.
     */
    public PublicKey decodePublicKey(String base64PublicKey) throws InvalidPublicKeyException {
        if (base64PublicKey == null || base64PublicKey.isBlank()) {
            throw new InvalidPublicKeyException("Public key value is missing or empty");
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            return keyFactory.generatePublic(keySpec);
        } catch (IllegalArgumentException e) {
            throw new InvalidPublicKeyException("Public key value is not valid Base64", e);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            throw new InvalidPublicKeyException("Public key value is not a valid RSA public key", e);
        }
    }

    /**
     * Computes the SHA-256 fingerprint of a public key's X.509-encoded
     * form, rendered as uppercase colon-separated hex - identical format
     * to Android's CryptoManager.generateFingerprint(), so fingerprints
     * computed here always compare equal to ones computed on-device for
     * the same key.
     */
    public String computeFingerprint(PublicKey publicKey) {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(publicKey.getEncoded());
            return toColonSeparatedHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on any standard JVM - this is
            // not a reachable failure in practice, but is not swallowed.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Verifies a Base64-encoded SHA256withRSA signature against the given
     * data and public key. Returns {@code false} (never throws) for a
     * structurally malformed or simply non-matching signature, since a
     * failed verification is an expected outcome when validating
     * untrusted client-submitted data - not an exceptional program state.
     * This mirrors Android's own CryptoManager.verify() behavior exactly.
     */
    public boolean verify(byte[] data, String base64Signature, PublicKey publicKey) {
        if (data == null || data.length == 0 || publicKey == null) {
            return false;
        }
        if (base64Signature == null || base64Signature.isBlank()) {
            return false;
        }

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(base64Signature);
        } catch (IllegalArgumentException malformedBase64) {
            return false;
        }

        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (SignatureException malformedSignatureBytes) {
            return false;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // Genuine platform/algorithm failure unrelated to the
            // signature's correctness - should not happen on a standard
            // JVM, but is not silently treated as "verification failed"
            // since that could mask a real configuration problem.
            throw new IllegalStateException("Signature verification failed due to a platform error", e);
        }
    }

    private String toColonSeparatedHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            builder.append(String.format(Locale.US, "%02X", bytes[i]));
            if (i != bytes.length - 1) {
                builder.append(FINGERPRINT_SEPARATOR);
            }
        }
        return builder.toString();
    }

    /**
     * Thrown when a client-submitted public key string cannot be decoded
     * into a usable RSA public key.
     */
    public static final class InvalidPublicKeyException extends Exception {

        public InvalidPublicKeyException(String message) {
            super(message);
        }

        public InvalidPublicKeyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
