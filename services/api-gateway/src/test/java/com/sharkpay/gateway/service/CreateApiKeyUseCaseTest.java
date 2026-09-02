package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.fakes.InMemoryApiKeyRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Create-api-key: the plaintext secret exists exactly once in the result;
 * storage contains only the SHA-256 hash (hash-never-plaintext), quotas
 * default, and the repository is keyed by the generated id.
 */
class CreateApiKeyUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    private final InMemoryApiKeyRepository keys = new InMemoryApiKeyRepository();
    private final SequentialRandomness randomness = new SequentialRandomness();
    private final CreateApiKeyUseCase useCase =
            new CreateApiKeyUseCase(keys, randomness, new MutableClock(NOW));

    @Test
    void plaintextSecretIsReturnedExactlyOnceAndOnlyItsHashIsPersisted() {
        UUID principal = UUID.randomUUID();
        CreateApiKeyUseCase.Result result = useCase.create(principal,
                Set.of(Scope.PAYMENTS_READ, Scope.PAYMENTS_WRITE), null, null);

        assertNotNull(result.plaintext());
        assertTrue(result.plaintext().startsWith("sp_live_"), result.plaintext());
        assertEquals("sp_live_".length() + 43, result.plaintext().length());

        // the store contains the hash, never the plaintext — audited over
        // everything ever persisted by the fake's test oracle
        assertEquals(1, keys.all().size());
        for (ApiKey stored : keys.all().values()) {
            assertNotEquals(result.plaintext(), stored.secretHash());
            assertEquals(KeyHasher.sha256Hex(result.plaintext()), stored.secretHash());
            assertTrue(stored.secretHash().matches("^[0-9a-f]{64}$"));
            // no field of the persisted key carries the plaintext: the id,
            // principal, scopes, status and limits are all non-secret shapes
            assertEquals(result.plaintext().length() + 1,
                    result.plaintext().replace(stored.secretHash(), "").length() + 1);
        }
        assertEquals(result.key().id(), storedKey(result).id());
    }

    private ApiKey storedKey(CreateApiKeyUseCase.Result result) {
        return keys.findById(result.key().id()).orElseThrow();
    }

    @Test
    void createdKeyIsActiveWithGivenScopesAndTimestamps() {
        UUID principal = UUID.randomUUID();
        CreateApiKeyUseCase.Result result = useCase.create(principal, Set.of(Scope.FX_READ),
                60, 1_000L);
        ApiKey stored = storedKey(result);

        assertEquals(ApiKeyStatus.ACTIVE, stored.status());
        assertEquals(principal, stored.principalId());
        assertEquals(Set.of(Scope.FX_READ), stored.scopes());
        assertEquals(60, stored.rpmLimit());
        assertEquals(1_000L, stored.monthlyLimit());
        assertEquals(NOW, stored.createdAt());
        assertEquals(NOW, stored.updatedAt());
        assertTrue(stored.authenticatesAt(NOW));
    }

    @Test
    void quotasFallBackToTheDocumentedDefaults() {
        CreateApiKeyUseCase.Result zeroRpm = useCase.create(UUID.randomUUID(),
                Set.of(Scope.OPS_READ), 0, 0L);
        assertEquals(CreateApiKeyUseCase.DEFAULT_RPM_LIMIT, zeroRpm.key().rpmLimit());
        assertEquals(CreateApiKeyUseCase.DEFAULT_MONTHLY_LIMIT, zeroRpm.key().monthlyLimit());

        CreateApiKeyUseCase.Result negative = useCase.create(UUID.randomUUID(),
                Set.of(Scope.OPS_READ), -5, -7L);
        assertEquals(CreateApiKeyUseCase.DEFAULT_RPM_LIMIT, negative.key().rpmLimit());
        assertEquals(CreateApiKeyUseCase.DEFAULT_MONTHLY_LIMIT, negative.key().monthlyLimit());

        CreateApiKeyUseCase.Result explicit = useCase.create(UUID.randomUUID(),
                Set.of(Scope.OPS_READ), 17, 34L);
        assertEquals(17, explicit.key().rpmLimit());
        assertEquals(34L, explicit.key().monthlyLimit());
    }

    @Test
    void scopesAndPrincipalAreRequired() {
        assertThrows(NullPointerException.class,
                () -> useCase.create(null, Set.of(Scope.OPS_READ), null, null));
        assertThrows(NullPointerException.class,
                () -> useCase.create(UUID.randomUUID(), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> useCase.create(UUID.randomUUID(), Set.of(), null, null));
        assertEquals(0, keys.all().size());
    }

    @Test
    void everyCreationMintsAFreshIdAndSecret() {
        UUID principal = UUID.randomUUID();
        CreateApiKeyUseCase.Result first = useCase.create(principal, Set.of(Scope.OPS_READ),
                null, null);
        CreateApiKeyUseCase.Result second = useCase.create(principal, Set.of(Scope.OPS_READ),
                null, null);
        assertNotEquals(first.key().id(), second.key().id());
        assertNotEquals(first.plaintext(), second.plaintext());
        assertEquals(2, keys.all().size());
    }
}
