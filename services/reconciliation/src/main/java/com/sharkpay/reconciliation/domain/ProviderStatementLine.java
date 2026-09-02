package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of a provider's reconciliation report — the shape produced by
 * the providers gateway {@code POST /v1/providers/{name}/reconcile}
 * ({@code provider.ProviderLine}: ref, raw wire status, amount, fee,
 * occurred-at). The port adapter owns the wire mapping; the domain consumes
 * this canonical shape only.
 *
 * @param ref        the provider-side transfer reference (match key)
 * @param status     the provider's <i>raw</i> status string (mapped later,
 *                   never guessed)
 * @param amount     the principal movement (integer minor units)
 * @param fee        the provider's fee for the movement
 * @param occurredAt when the movement occurred at the provider
 */
public record ProviderStatementLine(String ref, String status, Money amount, Money fee,
                                    Instant occurredAt) {

    public ProviderStatementLine {
        Objects.requireNonNull(ref, "ref is required");
        if (ref.isBlank()) {
            throw new IllegalArgumentException("provider line ref must not be blank");
        }
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(fee, "fee is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
    }
}
