package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.fakes.InMemoryApiKeyRepository;
import com.sharkpay.gateway.fakes.SequentialRandomness;
import com.sharkpay.gateway.testsupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Revoke (immediate, idempotent) + list (scoped, paginated, secret-free). */
class ApiKeyAdminUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();

    private final InMemoryApiKeyRepository keys = new InMemoryApiKeyRepository();
    private final MutableClock clock = new MutableClock(NOW);
    private final CreateApiKeyUseCase create =
            new CreateApiKeyUseCase(keys, new SequentialRandomness(), clock);
    private final ApiKeyAdminUseCase admin = new ApiKeyAdminUseCase(keys, clock);

    @Test
    void revokeKillsTheKeyImmediatelyAndIdempotently() {
        CreateApiKeyUseCase.Result result = create.create(PRINCIPAL,
                Set.of(Scope.PAYMENTS_READ), null, null);
        clock.advance(java.time.Duration.ofSeconds(30));
        admin.revoke(result.key().id(), PRINCIPAL);

        assertEquals(ApiKeyStatus.REVOKED, keys.findById(result.key().id()).orElseThrow().status());
        assertTrue(keys.findById(result.key().id()).orElseThrow().updatedAt()
                .isAfter(keys.findById(result.key().id()).orElseThrow().createdAt()));

        // second revoke: no state change, no exception (idempotent 204)
        Instant updatedAt = keys.findById(result.key().id()).orElseThrow().updatedAt();
        admin.revoke(result.key().id(), PRINCIPAL);
        assertEquals(updatedAt, keys.findById(result.key().id()).orElseThrow().updatedAt());
    }

    @Test
    void revokedSecretsNeverAuthenticate() {
        CreateApiKeyUseCase.Result result = create.create(PRINCIPAL,
                Set.of(Scope.PAYMENTS_READ), null, null);
        admin.revoke(result.key().id(), PRINCIPAL);
        org.junit.jupiter.api.Assertions.assertFalse(
                keys.findById(result.key().id()).orElseThrow().authenticatesAt(NOW));
    }

    @Test
    void foreignKeysLookMissingOnRevoke() {
        CreateApiKeyUseCase.Result mine = create.create(PRINCIPAL,
                Set.of(Scope.PAYMENTS_READ), null, null);
        UUID other = UUID.randomUUID();
        assertThrows(NoSuchElementException.class, () -> admin.revoke(mine.key().id(), other));
        assertEquals(ApiKeyStatus.ACTIVE, keys.findById(mine.key().id()).orElseThrow().status());
        assertThrows(NoSuchElementException.class,
                () -> admin.revoke("key_missing0000000000000000", PRINCIPAL));
    }

    @Test
    void listIsScopedToTheCallerAndIdPaginated() {
        UUID other = UUID.randomUUID();
        create.create(PRINCIPAL, Set.of(Scope.PAYMENTS_READ), null, null);
        create.create(PRINCIPAL, Set.of(Scope.OPS_READ), null, null);
        create.create(other, Set.of(Scope.FX_READ), null, null);

        List<com.sharkpay.gateway.domain.ApiKey> mine = admin.list(PRINCIPAL, 50, null);
        assertEquals(2, mine.size());
        assertTrue(mine.stream().allMatch(key -> key.principalId().equals(PRINCIPAL)));
        // id-ordered
        assertTrue(mine.get(0).id().compareTo(mine.get(1).id()) < 0);

        // page size 1 + cursor walks the second key
        List<com.sharkpay.gateway.domain.ApiKey> pageOne = admin.list(PRINCIPAL, 1, null);
        assertEquals(1, pageOne.size());
        List<com.sharkpay.gateway.domain.ApiKey> pageTwo = admin.list(PRINCIPAL, 1,
                pageOne.get(0).id());
        assertEquals(1, pageTwo.size());
        assertEquals(mine.get(1).id(), pageTwo.get(0).id());

        // no page ever contains foreign keys or secret material
        assertTrue(admin.list(other, 50, null).stream()
                .allMatch(key -> key.principalId().equals(other)));
        assertTrue(mine.stream().allMatch(key -> key.secretHash().matches("^[0-9a-f]{64}$")));
    }
}
