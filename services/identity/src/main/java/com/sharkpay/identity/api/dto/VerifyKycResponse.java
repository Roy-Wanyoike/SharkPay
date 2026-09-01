package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.identity.service.VerifyKycUseCase;

/**
 * Response of POST /internal/v1/principals/{id}/kyc: the stored decision and
 * the principal after the decision was applied.
 */
public record VerifyKycResponse(
        @JsonProperty("principal") PrincipalResponse principal,
        @JsonProperty("kyc") KycRecordResponse kyc) {

    public static VerifyKycResponse from(VerifyKycUseCase.Result result) {
        return new VerifyKycResponse(
                PrincipalResponse.from(result.principal()),
                KycRecordResponse.from(result.record()));
    }
}
