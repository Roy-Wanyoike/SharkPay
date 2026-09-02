package com.sharkpay.gateway.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production randomness adapter: id/secret shapes, prefix conventions
 * and uniqueness — with obviously-fake entropy guarantees only (the values
 * ARE the real generator's output shape, but no real secret is ever pinned
 * in a test).
 */
class SecureRandomRandomnessTest {

    private final SecureRandomRandomness randomness = new SecureRandomRandomness();

    @Test
    void apiKeySecretsAreSkLivePlus43Base62Chars() {
        for (int i = 0; i < 25; i++) {
            String secret = randomness.apiKeySecret();
            assertTrue(secret.startsWith("sp_live_"), secret);
            assertEquals(51, secret.length());
            assertTrue(secret.matches("^sp_live_[0-9A-Za-z]{43}$"), secret);
        }
    }

    @Test
    void idsCarryTheirPrefixesAndAlphabets() {
        for (int i = 0; i < 25; i++) {
            assertTrue(randomness.apiKeyId().matches("^key_[0-9A-Za-z]{24}$"));
            assertTrue(randomness.webhookId().matches("^wh_[0-9A-Za-z]{24}$"));
            assertTrue(randomness.webhookDeliveryId().matches("^whd_[0-9A-Za-z]{24}$"));
            assertTrue(randomness.sandboxPaymentId().matches("^pay_[0-9A-Za-z]{24}$"));
        }
    }

    @Test
    void secretsAndIdsAreUnique() {
        Set<String> secrets = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            secrets.add(randomness.apiKeySecret());
            ids.add(randomness.apiKeyId());
        }
        assertEquals(200, secrets.size());
        assertEquals(200, ids.size());
        assertNotEquals(randomness.apiKeySecret(), randomness.apiKeySecret());
    }
}
