package com.sharkpay.wallet.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: does the wallet-owning principal exist, and what is its status?
 * The real adapter calls the identity service (gRPC/REST, Keycloak-backed);
 * tests use the in-tree fake. Wallet creation requires an ACTIVE principal.
 */
public interface PrincipalLookup {

    /** The identity service's view of a principal, or empty when unknown. */
    Optional<PrincipalSnapshot> findById(UUID principalId);

    /**
     * The wallet service's projection of a principal's status (values mirror
     * the identity service: ACTIVE / SUSPENDED / CLOSED).
     */
    enum PrincipalStatus {
        ACTIVE, SUSPENDED, CLOSED
    }

    /** Snapshot of the fields the wallet service cares about. */
    record PrincipalSnapshot(UUID id, PrincipalStatus status) {
    }
}
