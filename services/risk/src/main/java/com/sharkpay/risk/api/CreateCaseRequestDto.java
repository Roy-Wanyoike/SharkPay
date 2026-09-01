package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** {@code POST /internal/v1/risk/cases} body. */
public record CreateCaseRequestDto(
        @JsonProperty("subject_principal_id") @NotBlank String subjectPrincipalId,
        @JsonProperty("reason") @NotBlank String reason) {
}
