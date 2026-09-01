package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sharkpay.risk.domain.CaseTransition;

/** One recorded case transition (4-eyes audit: actor + time). */
public record CaseTransitionDto(
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("actor") String actor,
        @JsonProperty("resolution") String resolution,
        @JsonProperty("occurred_at") String occurredAt) {

    public static CaseTransitionDto from(CaseTransition t) {
        return new CaseTransitionDto(t.from().wire(), t.to().wire(), t.actor(),
                t.resolution() == null ? null : t.resolution().wire(), t.occurredAt().toString());
    }
}
