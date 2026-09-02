package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.ports.PayoutRepository;

import java.util.Objects;

/**
 * Read-side use-case: fetch a payout by id (404 when unknown).
 */
public final class GetPayoutUseCase {

    private final PayoutRepository payouts;

    public GetPayoutUseCase(PayoutRepository payouts) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
    }

    public Payout get(String payoutId) {
        Objects.requireNonNull(payoutId, "payoutId is required");
        return payouts.findById(payoutId.trim())
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "payout " + payoutId + " not found"));
    }
}
