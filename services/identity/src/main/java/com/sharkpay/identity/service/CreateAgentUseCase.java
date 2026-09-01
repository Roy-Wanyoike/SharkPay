package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;

/**
 * Creates an AGENT principal owned by the principal identified by the given
 * SharkId. Delegates owner validation and creation to
 * {@link CreatePrincipalUseCase}.
 */
public final class CreateAgentUseCase {

    private final CreatePrincipalUseCase createPrincipal;

    public CreateAgentUseCase(CreatePrincipalUseCase createPrincipal) {
        this.createPrincipal = createPrincipal;
    }

    public CreatePrincipalUseCase.Result execute(SharkId ownerSharkId, String idempotencyKey) {
        return createPrincipal.execute(
                new CreatePrincipalUseCase.Command(PrincipalType.AGENT, ownerSharkId, idempotencyKey));
    }
}
