package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.ports.PrincipalLookup;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PrincipalLookup fake (src/test, per ADR 003): principals are registered programmatically
 * (tests) — the real adapter calls the identity service. Executable
 * specification of the contract: unknown ids return empty; statuses come
 * from the identity domain (ACTIVE / SUSPENDED / CLOSED).
 */
public final class FakePrincipalLookup implements PrincipalLookup {

    private final Map<UUID, PrincipalSnapshot> principals = new ConcurrentHashMap<>();

    /** Registers a principal (ACTIVE by default). */
    public PrincipalSnapshot register(UUID principalId) {
        return register(principalId, PrincipalStatus.ACTIVE);
    }

    /** Registers a principal with an explicit status. */
    public PrincipalSnapshot register(UUID principalId, PrincipalStatus status) {
        PrincipalSnapshot snapshot = new PrincipalSnapshot(principalId, status);
        principals.put(principalId, snapshot);
        return snapshot;
    }

    @Override
    public Optional<PrincipalSnapshot> findById(UUID principalId) {
        return Optional.ofNullable(principalId == null ? null : principals.get(principalId));
    }
}
