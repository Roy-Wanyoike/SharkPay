package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.PostingDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /internal/recon/breaks/{id}/compensations}: operator
 * A's draft (RB-7 step 2) — the requester, the reason, and the legs that
 * make both sides agree.
 */
public record ProposeCompensationRequest(
        @NotBlank(message = "requester is required") @Size(max = 128) String requester,
        @NotBlank(message = "reason is required") @Size(max = 400) String reason,
        @NotNull(message = "legs are required") @Size(min = 2, max = 64) List<LegJson> legs,
        java.util.UUID reverses_entry_id) {

    /**
     * One drafted leg: the ledger account ({@code suspense:recon:KES},
     * {@code honeycoin:settlement:KES}, …), the side, the positive amount
     * in integer minor units and its currency.
     */
    public record LegJson(
            @NotBlank(message = "account_ref is required") @Size(max = 128) String account_ref,
            @NotBlank(message = "direction is required") String direction,
            @Positive(message = "amount_minor must be positive") long amount_minor,
            @NotBlank(message = "currency is required") String currency) {

        /** Maps onto the domain leg (money validated by the library). */
        public CompensationLeg toDomain() {
            PostingDirection dir = PostingDirection.fromWireName(direction.trim().toLowerCase());
            Money amount = Money.of(amount_minor, currency);
            return new CompensationLeg(account_ref.trim(), dir, amount);
        }
    }

    /** Maps all legs onto the domain vocabulary. */
    public List<CompensationLeg> domainLegs() {
        return legs.stream().map(LegJson::toDomain).toList();
    }
}
