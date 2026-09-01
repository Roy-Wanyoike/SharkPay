package com.sharkpay.wallet.config;

import com.sharkpay.wallet.ports.PrincipalLookup;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-fast placeholder {@link PrincipalLookup} adapter: resolving a
 * principal requires the identity service's internal REST API (Keycloak
 * JWT-backed), which is wired at integration time by the integrator
 * (ADR 003 §3 — REST clients land once, centrally).
 *
 * <p>Refusing loudly per call (instead of silently accepting unknown
 * principals) keeps the money path honest: no wallet can be created for a
 * principal that was never verified against the identity service.</p>
 */
public final class IntegrationPendingPrincipalLookup implements PrincipalLookup {

    @Override
    public Optional<PrincipalSnapshot> findById(UUID principalId) {
        throw new IllegalStateException("PrincipalLookup adapter is not wired yet: the identity"
                + " REST client lands at integration time (ADR 003)."
                + " Cannot resolve principal " + principalId + ".");
    }
}
