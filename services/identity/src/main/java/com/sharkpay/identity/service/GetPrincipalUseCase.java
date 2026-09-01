package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only principal lookups by id and by SharkId.
 */
public final class GetPrincipalUseCase {

    private final PrincipalRepository principalRepository;

    public GetPrincipalUseCase(PrincipalRepository principalRepository) {
        this.principalRepository = principalRepository;
    }

    public Optional<Principal> byId(UUID principalId) {
        return principalRepository.findById(principalId);
    }

    public Optional<Principal> bySharkId(SharkId sharkId) {
        return principalRepository.findBySharkId(sharkId);
    }
}
