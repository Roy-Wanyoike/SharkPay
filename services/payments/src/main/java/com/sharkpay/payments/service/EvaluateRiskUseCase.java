package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.RiskPort;

import java.time.Clock;
import java.util.Objects;

/**
 * Pre-authorization risk gate (workflow activity + internal re-evaluation):
 * evaluates the intent while it is CREATED and applies the fail-closed
 * outcome. DENY and REVIEW both land in BLOCKED
 * (docs/STATE-MACHINES.md §1 "risk deny", no money moved); idempotent — an
 * intent that already moved past CREATED is not re-evaluated (ADR 003 G2: no
 * double effect).
 */
public final class EvaluateRiskUseCase {

    private final PaymentRepository payments;
    private final RiskPort risk;
    private final Clock clock;

    public EvaluateRiskUseCase(PaymentRepository payments, RiskPort risk, Clock clock) {
        this.payments = Objects.requireNonNull(payments, "paymentRepository is required");
        this.risk = Objects.requireNonNull(risk, "riskPort is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @return the risk decision (null when evaluation was skipped because the
     *         intent already moved past CREATED — idempotent replay)
     */
    public Result evaluate(String paymentId) {
        PaymentIntent intent = load(paymentId);
        if (intent.state() != PaymentState.CREATED) {
            return new Result(intent, null, true);
        }
        RiskPort.RiskDecision decision = risk.evaluate(new RiskPort.RiskEvaluation(
                intent.principalId(), intent.id(), intent.amount(), intent.rail().wireName(),
                intent.destination().internalWalletId().orElse(null),
                RiskPort.Phase.PRE_AUTHORIZATION));
        if (decision.decision() != RiskPort.Decision.ALLOW) {
            String prefix = decision.decision() == RiskPort.Decision.DENY ? "risk_deny" : "risk_review";
            intent.markBlocked(prefix + ": " + String.join("; ", decision.reasons()),
                    clock.instant());
            payments.save(intent);
        }
        return new Result(intent, decision, false);
    }

    private PaymentIntent load(String paymentId) {
        return payments.findById(Objects.requireNonNull(paymentId, "paymentId is required"))
                .orElseThrow(() -> new com.sharkpay.payments.domain.UnknownPaymentException(paymentId));
    }

    /**
     * @param intent  the intent after evaluation (BLOCKED when denied)
     * @param decision the risk decision (null when skipped)
     * @param skipped  true when the intent was already past CREATED
     */
    public record Result(PaymentIntent intent, RiskPort.RiskDecision decision, boolean skipped) {
    }
}
