package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.ports.PrincipalLookup;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link PrincipalLookup} fake (the identity REST adapter lands
 * at integration, ADR 003 §3). Principal snapshots are registered by test;
 * unregistered ids return empty so the use-cases exercise their 4xx paths.
 */
public final class FakePrincipalLookup implements PrincipalLookup {

    private final Map<UUID, PrincipalSnapshot> principals = new ConcurrentHashMap<>();

    /** Registers an ACTIVE / LIMITED principal (the payout-capable default). */
    public FakePrincipalLookup addActiveLimited(UUID principalId) {
        principals.put(principalId, new PrincipalSnapshot(principalId, PrincipalStatus.ACTIVE,
                KycTier.LIMITED));
        return this;
    }

    /** Registers a principal snapshot verbatim. */
    public FakePrincipalLookup add(UUID principalId, PrincipalStatus status, KycTier kycTier) {
        principals.put(principalId, new PrincipalSnapshot(principalId, status, kycTier));
        return this;
    }

    @Override
    public Optional<PrincipalSnapshot> findById(UUID principalId) {
        return Optional.ofNullable(principals.get(principalId));
    }

    /** Whether the principal is registered. */
    public boolean knows(UUID principalId) {
        return principals.containsKey(principalId);
    }
}
