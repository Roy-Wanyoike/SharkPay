package com.sharkpay.risk.storage;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * JSON shapes persisted in the jsonb columns (evaluations.request,
 * evaluations.reasons, rule_sets.config). Wire strings only — enums are
 * round-tripped through their wire values so the stored documents are the
 * contract vocabulary, not Java identifiers.
 */
public final class PersistedShapes {

    private PersistedShapes() {
    }

    public record Money(
            @JsonProperty("amount_minor") long amountMinor,
            @JsonProperty("currency") String currency,
            @JsonProperty("exponent") int exponent) {
    }

    public record RuleResult(
            @JsonProperty("rule_id") String ruleId,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("reason") String reason) {
    }

    public record EvaluationRequest(
            @JsonProperty("evaluation_id") String evaluationId,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("subject_principal_id") String subjectPrincipalId,
            @JsonProperty("principal_type") String principalType,
            @JsonProperty("kyc_tier") String kycTier,
            @JsonProperty("amount") Money amount,
            @JsonProperty("channel") String channel,
            @JsonProperty("counterparty_shark_id") String counterpartySharkId,
            @JsonProperty("geo_country") String geoCountry,
            @JsonProperty("phase") String phase,
            @JsonProperty("transaction_type") String transactionType) {
    }

    public record Velocity(
            @JsonProperty("max_transactions") int maxTransactions,
            @JsonProperty("window_seconds") long windowSeconds) {
    }

    public record TierLimits(
            @JsonProperty("daily_minor") long dailyMinor,
            @JsonProperty("weekly_minor") long weeklyMinor,
            @JsonProperty("max_single_minor") Long maxSingleMinor,
            @JsonProperty("currency") String currency) {
    }

    public record RuleSet(
            @JsonProperty("rule_set_id") String ruleSetId,
            @JsonProperty("version") long version,
            @JsonProperty("active") boolean active,
            @JsonProperty("velocity") Velocity velocity,
            @JsonProperty("tier_limits") Map<String, TierLimits> tierLimits,
            @JsonProperty("agent_limits") TierLimits agentLimits,
            @JsonProperty("geo_denylist") List<String> geoDenylist,
            @JsonProperty("counterparty_denylist") List<String> counterpartyDenylist) {
    }
}
