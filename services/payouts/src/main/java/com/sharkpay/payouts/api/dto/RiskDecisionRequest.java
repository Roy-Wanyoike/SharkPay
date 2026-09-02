package com.sharkpay.payouts.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /internal/payouts/{id}/risk-decision body: the risk service's
 * verdict on a PENDING_RISK payout. {@code decision} is ALLOW or DENY.
 */
public record RiskDecisionRequest(@NotBlank String decision, String reason) {
}
