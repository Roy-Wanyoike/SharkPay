package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.ProviderGatewayPort;

import java.util.List;

/**
 * Fail-fast placeholder {@link ProviderGatewayPort} adapter: provider
 * routing/initiation/polling requires the Go provider gateway's REST API
 * (services/providers), wired at integration time by the integrator
 * (ADR 003 §3). Failing closed: no payment is initiated against a
 * non-existent gateway.
 */
public final class IntegrationPendingProviderGateway implements ProviderGatewayPort {

    @Override
    public List<ProviderCandidateView> candidates() {
        throw notWired("candidates");
    }

    @Override
    public Quote quote(QuoteRequest request) {
        throw notWired("quote " + request.rail());
    }

    @Override
    public ProviderRef initiate(InitiateRequest request) {
        throw notWired("initiate " + request.rail());
    }

    @Override
    public TransferStatus poll(ProviderRef ref) {
        throw notWired("poll " + ref.ref());
    }

    @Override
    public void cancel(ProviderRef ref) {
        throw notWired("cancel " + ref.ref());
    }

    private static IllegalStateException notWired(String operation) {
        return new IllegalStateException("ProviderGatewayPort adapter is not wired yet: the Go"
                + " provider gateway REST client lands at integration time (ADR 003)."
                + " Cannot " + operation + ".");
    }
}
