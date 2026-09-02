package com.sharkpay.payouts.config;

import com.sharkpay.payouts.ports.ProviderGatewayPort;

/**
 * Fail-fast placeholder {@link ProviderGatewayPort} adapter: submitting,
 * polling or cancelling rail transfers requires the providers service
 * gateway (the uniform Provider abstraction, ARCHITECTURE.md §4.2), wired
 * at integration time by the integrator (ADR 003 §3).
 */
public final class IntegrationPendingProviderGateway implements ProviderGatewayPort {

    @Override
    public ProviderRef initiate(InitiateSubmission command) {
        throw new IllegalStateException("ProviderGatewayPort adapter is not wired yet: the "
                + "providers REST adapter lands at integration time (ADR 003). Cannot submit "
                + command.payoutId() + " to " + command.rail() + ".");
    }

    @Override
    public ProviderStatus poll(ProviderRef ref) {
        throw new IllegalStateException("ProviderGatewayPort adapter is not wired yet: the "
                + "providers REST adapter lands at integration time (ADR 003). Cannot poll "
                + ref.provider() + ":" + ref.ref() + ".");
    }

    @Override
    public void cancel(ProviderRef ref) {
        throw new IllegalStateException("ProviderGatewayPort adapter is not wired yet: the "
                + "providers REST adapter lands at integration time (ADR 003). Cannot cancel "
                + ref.provider() + ":" + ref.ref() + ".");
    }
}
