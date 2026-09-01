package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Phase;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.TransactionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code POST /internal/v1/risk/evaluations}. All enums use
 * the lowercase contract wire values.
 */
public record EvaluationRequestDto(
        @JsonProperty("evaluation_id") @NotBlank String evaluationId,
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("subject_principal_id") @NotBlank String subjectPrincipalId,
        @JsonProperty("principal_type") @NotBlank String principalType,
        @JsonProperty("kyc_tier") @NotBlank String kycTier,
        @JsonProperty("amount") @NotNull @Valid MoneyDto amount,
        @JsonProperty("channel") @NotBlank String channel,
        @JsonProperty("counterparty_shark_id") String counterpartySharkId,
        @JsonProperty("geo_country") String geoCountry,
        @JsonProperty("phase") String phase,
        @JsonProperty("transaction_type") String transactionType) {

    public EvaluationRequest toDomain() {
        return new EvaluationRequest(
                evaluationId,
                transactionId,
                subjectPrincipalId,
                com.sharkpay.risk.domain.WireValue.parse(PrincipalType.class, principalType, "principal_type"),
                com.sharkpay.risk.domain.WireValue.parse(KycTier.class, kycTier, "kyc_tier"),
                amount == null ? null : amount.toMoney(),
                com.sharkpay.risk.domain.WireValue.parse(Channel.class, channel, "channel"),
                counterpartySharkId,
                geoCountry,
                phase == null ? Phase.PRE : com.sharkpay.risk.domain.WireValue.parse(Phase.class, phase, "phase"),
                transactionType == null ? null
                        : com.sharkpay.risk.domain.WireValue.parse(TransactionType.class, transactionType, "transaction_type"));
    }
}
