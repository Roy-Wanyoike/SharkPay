package com.sharkpay.payments.domain;

import java.util.List;

/**
 * Risk evaluation returned REVIEW (needs a human decision) on the
 * pre-authorization check, so the intent is not created — the caller retries
 * after the review clears. Maps to 422 {@code risk_blocked} with the risk
 * reasons in {@code error.details.reasons} (payments.yaml 422 codes).
 *
 * <p>Contrast with a plain DENY: that creates a persisted intent in
 * {@link PaymentState#BLOCKED} (docs/STATE-MACHINES.md §1, "risk deny", no
 * money moved) which is returned as a 201 with the BLOCKED state.</p>
 */
public class RiskReviewException extends PaymentDomainException {

    private final List<String> reasons;

    public RiskReviewException(List<String> reasons) {
        super("payment blocked by risk review: " + String.join("; ", reasons));
        this.reasons = List.copyOf(reasons);
    }

    public List<String> reasons() {
        return reasons;
    }
}
