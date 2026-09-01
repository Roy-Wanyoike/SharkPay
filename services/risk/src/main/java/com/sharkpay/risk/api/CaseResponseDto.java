package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.risk.domain.Case;

import java.util.List;

/** Compliance case representation (ids are the public {@code case_<hex>} form). */
public record CaseResponseDto(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("subject_principal_id") String subjectPrincipalId,
        @JsonProperty("reason") String reason,
        @JsonProperty("status") String status,
        @JsonProperty("assigned_to") String assignedTo,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("transitions") List<CaseTransitionDto> transitions) {

    public static CaseResponseDto from(Case c) {
        return new CaseResponseDto(
                c.publicId(),
                c.subjectPrincipalId(),
                c.reason(),
                c.status().wire(),
                c.assignedTo(),
                c.createdAt().toString(),
                c.updatedAt().toString(),
                c.transitions().stream().map(CaseTransitionDto::from).toList());
    }
}
