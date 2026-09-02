package com.sharkpay.payouts.config;

import com.sharkpay.payouts.ports.PrincipalLookup;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-fast placeholder {@link PrincipalLookup} adapter: principal
 * snapshots come from the identity service, wired at integration time by
 * the integrator (ADR 003 §3).
 */
public final class IntegrationPendingPrincipalLookup implements PrincipalLookup {

    @Override
    public Optional<PrincipalSnapshot> findById(UUID principalId) {
        throw new IllegalStateException("PrincipalLookup adapter is not wired yet: the identity "
                + "REST adapter lands at integration time (ADR 003). Cannot read principal "
                + principalId + ".");
    }
}
