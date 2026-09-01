package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.PrincipalResolver;

import java.util.UUID;

/**
 * Fixed-principal {@link PrincipalResolver} fake: standalone MockMvc tests
 * bypass security, so the JWT subject is substituted by a fixed UUID.
 */
public final class FixedPrincipalResolver implements PrincipalResolver {

    private final UUID principalId;

    public FixedPrincipalResolver(UUID principalId) {
        this.principalId = principalId;
    }

    /** A resolver pinned to a random principal. */
    public static FixedPrincipalResolver random() {
        return new FixedPrincipalResolver(UUID.randomUUID());
    }

    @Override
    public UUID resolve() {
        return principalId;
    }

    /** The pinned principal id. */
    public UUID principalId() {
        return principalId;
    }
}
