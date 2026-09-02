package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ApiKey lifecycle: rotation grace semantics, revocation, validation. */
class ApiKeyTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final Set<Scope> SCOPES = Set.of(Scope.PAYMENTS_READ, Scope.PAYMENTS_WRITE);

    private static ApiKey active() {
        return ApiKey.active("key_000000000000000000000001", PRINCIPAL,
                "a".repeat(64), SCOPES, 300, 1000L, NOW);
    }

    @Test
    void activeKeyAuthenticatesAtAnyTime() {
        ApiKey key = active();
        assertTrue(key.authenticatesAt(NOW));
        assertTrue(key.authenticatesAt(NOW.plusSeconds(10_000_000)));
        assertEquals(ApiKeyStatus.ACTIVE, key.status());
        assertTrue(key.graceExpiresAtOptional().isEmpty());
    }

    @Test
    void rotationKeepsOldSecretValidExactlyUntilGraceExpiry() {
        ApiKey rotating = active().markRotating(NOW);

        assertEquals(ApiKeyStatus.ROTATING, rotating.status());
        assertEquals(NOW.plus(ApiKey.GRACE_WINDOW), rotating.graceExpiresAt());

        // one nanosecond before expiry: still valid
        assertTrue(rotating.authenticatesAt(rotating.graceExpiresAt().minusNanos(1)));
        // at and after expiry: rejected exactly like an unknown key
        assertFalse(rotating.authenticatesAt(rotating.graceExpiresAt()));
        assertFalse(rotating.authenticatesAt(rotating.graceExpiresAt().plusSeconds(1)));
    }

    @Test
    void graceWindowIsTheDocumented24Hours() {
        assertEquals(java.time.Duration.ofHours(24), ApiKey.GRACE_WINDOW);
    }

    @Test
    void rotationPreservesIdentityScopesAndQuotas() {
        ApiKey rotating = active().markRotating(NOW);
        assertEquals(active().id(), rotating.id());
        assertEquals(PRINCIPAL, rotating.principalId());
        assertEquals(SCOPES, rotating.scopes());
        assertEquals(300, rotating.rpmLimit());
        assertEquals(1000L, rotating.monthlyLimit());
        assertEquals(active().secretHash(), rotating.secretHash());
    }

    @Test
    void revokedKeyNeverAuthenticates() {
        ApiKey revoked = active().revoked(NOW);
        assertEquals(ApiKeyStatus.REVOKED, revoked.status());
        assertFalse(revoked.authenticatesAt(NOW));
        assertFalse(revoked.authenticatesAt(NOW.minusSeconds(1)));
        assertNull(revoked.graceExpiresAt());
    }

    @Test
    void scopeCheckIsFailClosed() {
        ApiKey key = active();
        assertTrue(key.hasScope(Scope.PAYMENTS_READ));
        assertTrue(key.hasScope(Scope.PAYMENTS_WRITE));
        assertFalse(key.hasScope(Scope.WEBHOOKS_MANAGE));
        assertFalse(key.hasScope(Scope.API_KEYS_MANAGE));
    }

    @Test
    void secretHashMustBe64HexChars() {
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "short", SCOPES, 1, 1L, NOW));
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "A".repeat(64), SCOPES, 1, 1L, NOW));
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "g".repeat(64), SCOPES, 1, 1L, NOW));
    }

    @Test
    void keyRequiresScopesAndPositiveQuotas() {
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "a".repeat(64), Set.of(), 1, 1L, NOW));
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "a".repeat(64), SCOPES, 0, 1L, NOW));
        assertThrows(IllegalArgumentException.class, () -> ApiKey.active("key_x", PRINCIPAL,
                "a".repeat(64), SCOPES, 1, 0L, NOW));
    }

    @Test
    void rotatingKeyRequiresGraceExpiryAndOthersMustNotHaveOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKey("key_x", PRINCIPAL, "a".repeat(64), SCOPES,
                        ApiKeyStatus.ROTATING, null, 1, 1L, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new ApiKey("key_x", PRINCIPAL, "a".repeat(64), SCOPES,
                        ApiKeyStatus.ACTIVE, NOW, 1, 1L, NOW, NOW));
    }

    @Test
    void markRotatingAndRevokedProduceFreshInstances() {
        ApiKey original = active();
        ApiKey rotating = original.markRotating(NOW);
        ApiKey revoked = original.revoked(NOW);
        assertNotEquals(original.status(), rotating.status());
        assertNotEquals(original.status(), revoked.status());
        assertEquals(ApiKeyStatus.ACTIVE, original.status());
    }

    @Test
    void statusWireNamesAreStable() {
        assertEquals("active", ApiKeyStatus.ACTIVE.wireName());
        assertEquals("rotating", ApiKeyStatus.ROTATING.wireName());
        assertEquals("revoked", ApiKeyStatus.REVOKED.wireName());
        assertEquals(ApiKeyStatus.ACTIVE, ApiKeyStatus.fromWire("active"));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStatus.fromWire("bogus"));
    }
}
