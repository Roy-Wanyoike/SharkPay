package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;

/**
 * One classified discrepancy produced by the {@link ComparisonEngine} —
 * everything a {@link ReconBreak} needs before lifecycle and aging are
 * attached. Money/status fields are null on the side that is absent
 * (MISSING_* breaks carry only one side).
 *
 * @param breakType      the taxonomy entry (never guessed)
 * @param providerRef    the provider-side reference, when known
 * @param internalRef    the internal-side reference, when known
 * @param providerAmount provider-side principal amount, when known
 * @param internalAmount internal-side principal amount, when known
 * @param providerFee    provider-side fee, when known
 * @param internalFee    internal-side fee, when known
 * @param providerStatus provider-side raw status string, when known
 * @param internalStatus internal-side raw status string, when known
 */
public record DetectedBreak(BreakType breakType, String providerRef, String internalRef,
                            Money providerAmount, Money internalAmount, Money providerFee,
                            Money internalFee, String providerStatus, String internalStatus) {
}
