package com.sharkpay.risk.storage;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.Phase;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationMapperTest {

    private static final String EVALUATION_ID = "1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00";
    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    private static EvaluationRequest request() {
        return new EvaluationRequest(EVALUATION_ID, "txn-42", "subject-1", PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(100_00L, "KES"), Channel.WALLET, "shark_9", "KE",
                Phase.POST, TransactionType.TRANSFER);
    }

    private static Evaluation evaluation() {
        return new Evaluation(EVALUATION_ID, request(), Decision.REVIEW,
                List.of(new RuleResult("velocity_window", Outcome.PASS, "velocity ok: 0/10"),
                        new RuleResult("tier_limit", Outcome.REVIEW, "near cap")), T0);
    }

    @Test
    void roundTripsTheDomainEvaluation() {
        Evaluation original = evaluation();

        EvaluationEntity entity = EvaluationMapper.toEntity(original);
        Evaluation restored = EvaluationMapper.toDomain(entity);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.request()).isEqualTo(original.request());
        assertThat(restored.ruleResults()).containsExactlyElementsOf(original.ruleResults());
    }

    @Test
    void entityCarriesTheRowShape() {
        EvaluationEntity entity = EvaluationMapper.toEntity(evaluation());

        assertThat(entity.id).isEqualTo(UUID.fromString(EVALUATION_ID));
        assertThat(entity.subjectPrincipalId).isEqualTo("subject-1");
        assertThat(entity.decision).isEqualTo("review");
        assertThat(entity.createdAt).isEqualTo(T0);
        assertThat(entity.reasons).contains("\"rule_id\":\"velocity_window\"")
                .contains("\"outcome\":\"pass\"")
                .contains("\"reason\":\"velocity ok: 0/10\"");
        assertThat(entity.request)
                .contains("\"evaluation_id\":\"" + EVALUATION_ID + "\"")
                .contains("\"transaction_id\":\"txn-42\"")
                .contains("\"principal_type\":\"individual\"")
                .contains("\"kyc_tier\":\"limited\"")
                .contains("\"amount_minor\":10000")
                .contains("\"currency\":\"KES\"")
                .contains("\"exponent\":2")
                .contains("\"channel\":\"wallet\"")
                .contains("\"counterparty_shark_id\":\"shark_9\"")
                .contains("\"geo_country\":\"KE\"")
                .contains("\"phase\":\"post\"")
                .contains("\"transaction_type\":\"transfer\"");
    }

    @Test
    void emptyOptionalsPersistAsNullAndRoundTrip() {
        EvaluationRequest minimal = EvaluationRequest.of(EVALUATION_ID, "subject-1",
                PrincipalType.AGENT, KycTier.FULL, Money.of(1_00L, "USD"), Channel.FX);
        Evaluation evaluation = new Evaluation(EVALUATION_ID, minimal, Decision.ALLOW,
                List.of(new RuleResult("counterparty_denylist", Outcome.PASS, "no counterparty provided")), T0);

        Evaluation restored = EvaluationMapper.toDomain(EvaluationMapper.toEntity(evaluation));

        assertThat(restored).isEqualTo(evaluation);
        assertThat(restored.request().counterpartySharkId()).isNull();
        assertThat(restored.request().geoCountry()).isNull();
        // defaults re-derived by the domain constructor on rehydration
        assertThat(restored.request().transactionId()).isEqualTo(EVALUATION_ID);
        assertThat(restored.request().phase()).isEqualTo(Phase.PRE);
        assertThat(restored.request().transactionType()).isEqualTo(TransactionType.PAYMENT);
    }

    @Test
    void unknownDecisionWireFailsRehydration() {
        EvaluationEntity entity = EvaluationMapper.toEntity(evaluation());
        entity.decision = "maybe";

        assertThatThrownBy(() -> EvaluationMapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decision")
                .hasMessageContaining("maybe");
    }

    @Test
    void unknownOutcomeWireFailsRehydration() {
        EvaluationEntity entity = EvaluationMapper.toEntity(evaluation());
        entity.reasons = entity.reasons.replace("\"pass\"", "\"warn\"");

        assertThatThrownBy(() -> EvaluationMapper.toDomain(entity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outcome")
                .hasMessageContaining("warn");
    }

    @Test
    void unknownEnumWireInTheRequestFailsRehydration() {
        EvaluationEntity entity = EvaluationMapper.toEntity(evaluation());
        entity.request = entity.request.replace("\"wallet\"", "\"telepathy\"");

        assertThatThrownBy(() -> EvaluationMapper.toDomain(entity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void malformedJsonColumnsFailFast() {
        EvaluationEntity entity = EvaluationMapper.toEntity(evaluation());
        entity.request = "{not-json";

        assertThatThrownBy(() -> EvaluationMapper.toDomain(entity))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
