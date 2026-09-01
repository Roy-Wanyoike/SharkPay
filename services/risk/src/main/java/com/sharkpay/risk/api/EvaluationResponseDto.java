package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.risk.domain.RuleResult;

import java.util.List;

/** {@code {evaluation_id, decision, reasons[]}} — POST + GET responses. */
public record EvaluationResponseDto(
        @JsonProperty("evaluation_id") String evaluationId,
        @JsonProperty("decision") String decision,
        @JsonProperty("reasons") List<RuleResultDto> reasons) {

    public static EvaluationResponseDto from(com.sharkpay.risk.domain.Evaluation evaluation) {
        return new EvaluationResponseDto(evaluation.evaluationId(), evaluation.decision().wire(),
                evaluation.ruleResults().stream().map(RuleResultDto::from).toList());
    }
}
