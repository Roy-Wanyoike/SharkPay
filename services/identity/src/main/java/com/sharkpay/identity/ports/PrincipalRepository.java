package com.sharkpay.identity.ports;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.SharkId;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for principals (owned by this service's hexagon).
 * Implementations must enforce SharkId uniqueness.
 */
public interface PrincipalRepository {

    /** Upsert (the principal id is the identity). Returns the stored state. */
    Principal save(Principal principal);

    Optional<Principal> findById(UUID id);

    Optional<Principal> findBySharkId(SharkId sharkId);
}
