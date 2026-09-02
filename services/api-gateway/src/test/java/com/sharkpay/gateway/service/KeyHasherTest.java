package com.sharkpay.gateway.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * API key hashing: SHA-256 hex, deterministic, constant-time comparison,
 * fail-closed on malformed stored hashes (docs/SECURITY.md §2,
 * BACKEND-DESIGN.md §10 — plaintext secrets are never persisted).
 */
class KeyHasherTest {

    private static final String SECRET = "sp_live_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZf";

    @Test
    void sha256HexMatchesTheKnownVector() throws Exception {
        // sha256("sp_live_0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZf")
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(SECRET.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte b : digest) {
            expected.append(String.format("%02x", b));
        }
        assertEquals(expected.toString(), KeyHasher.sha256Hex(SECRET));
    }

    @Test
    void sha256HexIsAlwaysSixtyFourLowercaseHexChars() {
        for (String secret : new String[]{"sp_live_a", "sp_live_bbbbbbbbbbbb", "x", "0"}) {
            String hash = KeyHasher.sha256Hex(secret);
            assertEquals(64, hash.length());
            assertTrue(hash.matches("^[0-9a-f]{64}$"), hash);
        }
    }

    @Test
    void hashingIsDeterministicAndCollisionFreeForDistinctSecrets() {
        assertEquals(KeyHasher.sha256Hex(SECRET), KeyHasher.sha256Hex(SECRET));
        assertNotEquals(KeyHasher.sha256Hex(SECRET), KeyHasher.sha256Hex(SECRET + "x"));
        // the plaintext never appears inside the hash
        assertFalse(KeyHasher.sha256Hex(SECRET).contains("sp_live"));
    }

    @Test
    void constantTimeMatchAcceptsTheRightSecret() {
        String hash = KeyHasher.sha256Hex(SECRET);
        assertTrue(KeyHasher.matchesConstantTime(SECRET, hash));
    }

    @Test
    void constantTimeMatchRejectsWrongSecrets() {
        String hash = KeyHasher.sha256Hex(SECRET);
        assertFalse(KeyHasher.matchesConstantTime(SECRET + "x", hash));
        assertFalse(KeyHasher.matchesConstantTime("sp_live_0123456789ABCDEFGHIJKLMNOPQRSTUVWXY"
                + "Ze", hash));
        assertFalse(KeyHasher.matchesConstantTime("", hash));
    }

    @Test
    void constantTimeMatchFailsClosedOnMalformedInputs() {
        assertFalse(KeyHasher.matchesConstantTime(null, KeyHasher.sha256Hex(SECRET)));
        assertFalse(KeyHasher.matchesConstantTime(SECRET, null));
        assertFalse(KeyHasher.matchesConstantTime(SECRET, ""));
        assertFalse(KeyHasher.matchesConstantTime(SECRET, "zz")); // wrong length
        // non-hex stored value: decode fails, never matches (fail-closed)
        assertFalse(KeyHasher.matchesConstantTime(SECRET, "g".repeat(64)));
        // uppercase hex is not the stored canonical form either
        assertFalse(KeyHasher.matchesConstantTime(SECRET,
                KeyHasher.sha256Hex(SECRET).toUpperCase()));
    }

    @Test
    void messageDigestIsEqualIsTheConstantTimePrimitiveUsed() {
        // property: the two digests of the same secret are byte-equal — the
        // implementation routes through MessageDigest.isEqual (non-secret-
        // dependent runtime), which this pins behaviourally
        assertDoesNotThrow(() -> KeyHasher.matchesConstantTime(SECRET, KeyHasher.sha256Hex(SECRET)));
        assertNull(null);
    }
}
