package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.RiskDeniedException;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;

/**
 * Risk-decision intake (internal surface feeding the BLOCKED state of
 * docs/STATE-MACHINES.md §2): a DENY verdict on a PENDING_RISK payout
 * blocks it before submission and releases the hold (full reversal — no
 * money moved). An ALLOW verdict is a no-op: the release scheduler already
 * owns the onward path. A DENY arriving after the payout left PENDING_RISK
 * is a 409 — the train has left, reversal is a provider-return matter.
 */
public final class HandleRiskDecisionUseCase {

    private static final Logger log = LoggerFactory.getLogger(HandleRiskDecisionUseCase.class);

    private final PayoutRepository payouts;
    private final LedgerPort ledger;
    private final Clock clock;

    public HandleRiskDecisionUseCase(PayoutRepository payouts, LedgerPort ledger, Clock clock) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** @param decision ALLOW or DENY (case-insensitive) */
    public Payout apply(String payoutId, String decision, String reason) {
        Objects.requireNonNull(payoutId, "payoutId is required");
        Objects.requireNonNull(decision, "decision is required");
        Payout payout = payouts.findById(payoutId.trim())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "payout " + payoutId + " not found"));
        String verdict = decision.trim().toUpperCase(Locale.ROOT);
        switch (verdict) {
            case "ALLOW" -> {
                log.info("risk ALLOW for payout {} — stays scheduled (releases at {})",
                        payout.id(), payout.executeAfter());
                return payout;
            }
            case "DENY" -> {
                if (payout.state() != PayoutState.PENDING_RISK
                        && payout.state() != PayoutState.CREATED) {
                    throw new RiskDeniedException(payout.id(), payout.state());
                }
                HoldReleaser.release(ledger, payout, "payout blocked by risk");
                payout.riskDeny(reason == null || reason.isBlank() ? "risk denied" : reason.trim(),
                        clock.instant());
                payouts.save(payout);
                log.info("payout {} BLOCKED by risk decision; hold released", payout.id());
                return payout;
            }
            default -> throw new IllegalArgumentException(
                    "risk decision must be ALLOW or DENY: " + decision);
        }
    }
}
