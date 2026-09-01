package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.RiskPort;

/**
 * Fail-fast placeholder {@link RiskPort} adapter: risk evaluation requires
 * the risk service's REST API (Keycloak JWT-backed), which is wired at
 * integration time by the integrator (ADR 003 §3 — REST clients land once,
 * centrally). Refusing loudly keeps the money path honest: no payment is
 * ever created without a real risk verdict.
 */
public final class IntegrationPendingRiskPort implements RiskPort {

    @Override
    public RiskDecision evaluate(RiskEvaluation evaluation) {
        throw new IllegalStateException("RiskPort adapter is not wired yet: the risk service"
                + " REST client lands at integration time (ADR 003)."
                + " Cannot evaluate payment " + evaluation.paymentId() + ".");
    }
}
