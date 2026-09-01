package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.risk.domain.RuleResult;

/** One entry of the ordered reason list: {@code {rule_id, outcome, reason}}. */
public record RuleResultDto(
        @JsonProperty("rule_id") String ruleId,
        @JsonProperty("outcome") String outcome,
        @JsonProperty("reason") String reason) {

    public static RuleResultDto from(RuleResult result) {
        return new RuleResultDto(result.ruleId(), result.outcome().wire(), result.reason());
    }
}
