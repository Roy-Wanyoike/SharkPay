package com.sharkpay.gateway.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * API key secret hashing: SHA-256, hex-encoded (64 lowercase chars).
 * Plaintext secrets are hashed once at creation/rotation and never
 * persisted; authentication recomputes the digest and compares it against
 * the stored hash in constant time ({@link MessageDigest#isEqual}) — no
 * early-exit byte comparison on a secret-derived value.
 */
public final class KeyHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private KeyHasher() {
    }

    /** SHA-256 hex digest of the plaintext secret (the stored form). */
    public static String sha256Hex(String secret) {
        byte[] digest = digest(secret);
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xFF;
            hex[i * 2] = HEX[value >>> 4];
            hex[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(hex);
    }

    /**
     * Constant-time comparison of a presented secret against a stored hash:
     * hashes the presented secret and compares the digests with
     * {@link MessageDigest#isEqual}. Malformed stored hashes never match
     * (fail-closed).
     */
    public static boolean matchesConstantTime(String presentedSecret, String storedHashHex) {
        if (presentedSecret == null || storedHashHex == null
                || storedHashHex.length() != 64) {
            return false;
        }
        byte[] computed = digest(presentedSecret);
        byte[] stored = decodeHex(storedHashHex);
        if (stored == null) {
            return false;
        }
        return MessageDigest.isEqual(computed, stored);
    }

    private static byte[] digest(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] decodeHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            char highChar = hex.charAt(i * 2);
            char lowChar = hex.charAt(i * 2 + 1);
            // strict lowercase hex only — the canonical stored form; anything
            // else is malformed and never matches (fail-closed)
            if ((highChar < '0' || highChar > '9') && (highChar < 'a' || highChar > 'f')) {
                return null;
            }
            if ((lowChar < '0' || lowChar > '9') && (lowChar < 'a' || lowChar > 'f')) {
                return null;
            }
            int high = Character.digit(highChar, 16);
            int low = Character.digit(lowChar, 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
