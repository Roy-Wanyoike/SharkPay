package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStateException;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.fakes.InMemoryApiKeyRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotation semantics (docs/SECURITY.md §2 24 h overlap): the old secret
 * stays valid until grace expiry, the new secret is returned exactly once
 * (hash-only at rest), and the transition rules are enforced.
 */
class RotateApiKeyUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final Set<Scope> SCOPES = Set.of(Scope.PAYMENTS_READ, Scope.PAYMENTS_WRITE);

    private final InMemoryApiKeyRepository keys = new InMemoryApiKeyRepository();
    private final SequentialRandomness randomness = new SequentialRandomness();
    private final MutableClock clock = new MutableClock(NOW);
    private final RotateApiKeyUseCase useCase = new RotateApiKeyUseCase(keys, randomness, clock);
    private final CreateApiKeyUseCase create =
            new CreateApiKeyUseCase(keys, randomness, clock);

    private CreateApiKeyUseCase.Result seedKey() {
        return create.create(PRINCIPAL, SCOPES, 120, 5_000L);
    }

    @Test
    void rotationDemotesTheOldSecretAndMintsAFreshOneForTheSamePrincipal() {
        CreateApiKeyUseCase.Result original = seedKey();
        RotateApiKeyUseCase.Result rotation = useCase.rotate(original.key().id(), PRINCIPAL);

        // old key: ROTATING with the 24 h grace window, old secret intact
        assertEquals(ApiKeyStatus.ROTATING, rotation.demoted().status());
        assertEquals(original.key().id(), rotation.demoted().id());
        assertEquals(original.key().secretHash(), rotation.demoted().secretHash());
        assertEquals(NOW.plus(Duration.ofHours(24)), rotation.demoted().graceExpiresAt());
        assertTrue(rotation.demoted().authenticatesAt(NOW.plus(Duration.ofHours(23))));

        // new key: ACTIVE, same principal + scopes + quotas, fresh hash
        assertEquals(ApiKeyStatus.ACTIVE, rotation.fresh().status());
        assertEquals(PRINCIPAL, rotation.fresh().principalId());
        assertEquals(SCOPES, rotation.fresh().scopes());
        assertEquals(120, rotation.fresh().rpmLimit());
        assertEquals(5_000L, rotation.fresh().monthlyLimit());
        assertNotEquals(original.key().secretHash(), rotation.fresh().secretHash());

        // the plaintext of the new secret exists only in the result; the
        // store holds the hash (hash-never-plaintext)
        assertNotNull(rotation.plaintext());
        assertEquals(KeyHasher.sha256Hex(rotation.plaintext()), rotation.fresh().secretHash());
        assertEquals(2, keys.all().size()); // original row retained (now rotating), fresh row added
    }

    @Test
    void oldSecretKeepsWorkingUntilGraceExpiryThenFailsLikeUnknown() {
        CreateApiKeyUseCase.Result original = seedKey();
        RotateApiKeyUseCase.Result rotation = useCase.rotate(original.key().id(), PRINCIPAL);
        Instant grace = rotation.demoted().graceExpiresAt();

        MutableClock drift = new MutableClock(NOW);
        assertTrue(rotation.demoted().authenticatesAt(grace.minusNanos(1)));
        drift.set(grace);
        org.junit.jupiter.api.Assertions.assertFalse(rotation.demoted().authenticatesAt(grace));
        drift.set(grace.plusSeconds(1));
        org.junit.jupiter.api.Assertions.assertFalse(
                rotation.demoted().authenticatesAt(grace.plusSeconds(1)));

        // the new secret is not bound by the grace window
        assertTrue(rotation.fresh().authenticatesAt(grace.plusSeconds(1)));
    }

    @Test
    void onlyActiveKeysRotate() {
        CreateApiKeyUseCase.Result original = seedKey();
        RotateApiKeyUseCase.Result once = useCase.rotate(original.key().id(), PRINCIPAL);

        // rotating a ROTATING key is a state conflict
        ApiKeyStateException rotating = assertThrows(ApiKeyStateException.class,
                () -> useCase.rotate(original.key().id(), PRINCIPAL));
        assertTrue(rotating.getMessage().contains("only active keys can be rotated"));

        // revoking the fresh key then rotating it is also a conflict
        keys.save(once.fresh().revoked(NOW));
        ApiKeyStateException revoked = assertThrows(ApiKeyStateException.class,
                () -> useCase.rotate(once.fresh().id(), PRINCIPAL));
        assertTrue(revoked.getMessage().contains("revoked"));
    }

    @Test
    void foreignOrMissingKeysLookIdenticallyMissing() {
        CreateApiKeyUseCase.Result original = seedKey();
        UUID other = UUID.randomUUID();
        assertThrows(NoSuchElementException.class,
                () -> useCase.rotate(original.key().id(), other));
        assertThrows(NoSuchElementException.class,
                () -> useCase.rotate("key_doesnotexist0000000000000", PRINCIPAL));
    }

    @Test
    void rotationCarriesQuotasAndScopesForwardWithoutLeakingSecrets() {
        CreateApiKeyUseCase.Result original = seedKey();
        RotateApiKeyUseCase.Result rotation = useCase.rotate(original.key().id(), PRINCIPAL);
        for (ApiKey stored : keys.all().values()) {
            org.junit.jupiter.api.Assertions.assertFalse(stored.secretHash()
                    .startsWith("sp_live_"));
        }
        assertEquals(rotation.plaintext().length(), "sp_live_".length() + 43);
    }
}
