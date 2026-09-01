package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.Evaluation;

import java.util.List;
import java.util.UUID;

/**
 * Domain &lt;-&gt; entity mapping for evaluations. The request and reason
 * list are serialized as their persisted JSON shapes (jsonb columns).
 */
public final class EvaluationMapper {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private EvaluationMapper() {
    }

    public static EvaluationEntity toEntity(Evaluation evaluation) {
        PersistedShapes.EvaluationRequest request = toPersisted(evaluation.request());
        List<PersistedShapes.RuleResult> reasons = evaluation.ruleResults().stream()
                .map(EvaluationMapper::toPersisted)
                .toList();
        return new EvaluationEntity(
                UUID.fromString(evaluation.evaluationId()),
                evaluation.request().subjectPrincipalId(),
                evaluation.decision().wire(),
                JSON.writeValueAsString(reasons),
                JSON.writeValueAsString(request),
                evaluation.decidedAt());
    }

    public static Evaluation toDomain(EvaluationEntity entity) {
        PersistedShapes.EvaluationRequest persisted =
                JSON.readValue(entity.request, PersistedShapes.EvaluationRequest.class);
        List<PersistedShapes.RuleResult> reasons = JSON.readValue(entity.reasons,
                new tools.jackson.core.type.TypeReference<List<PersistedShapes.RuleResult>>() {
                });
        return new Evaluation(
                persisted.evaluationId(),
                toDomain(persisted),
                com.sharkpay.risk.domain.Decision.fromWire(entity.decision).orElseThrow(
                        () -> new IllegalStateException("unknown decision wire value: " + entity.decision)),
                reasons.stream().map(EvaluationMapper::toDomain).toList(),
                entity.createdAt);
    }

    private static PersistedShapes.RuleResult toPersisted(com.sharkpay.risk.domain.RuleResult result) {
        return new PersistedShapes.RuleResult(result.ruleId(), result.outcome().wire(), result.reason());
    }

    private static com.sharkpay.risk.domain.RuleResult toDomain(PersistedShapes.RuleResult persisted) {
        return new com.sharkpay.risk.domain.RuleResult(
                persisted.ruleId(),
                com.sharkpay.risk.domain.Outcome.fromWire(persisted.outcome()).orElseThrow(
                        () -> new IllegalStateException("unknown outcome wire value: " + persisted.outcome())),
                persisted.reason());
    }

    private static PersistedShapes.EvaluationRequest toPersisted(com.sharkpay.risk.domain.EvaluationRequest request) {
        return new PersistedShapes.EvaluationRequest(
                request.evaluationId(),
                request.transactionId(),
                request.subjectPrincipalId(),
                request.principalType().wire(),
                request.kycTier().wire(),
                new PersistedShapes.Money(
                        request.amount().amountMinor(),
                        request.amount().currency(),
                        request.amount().exponent()),
                request.channel().wire(),
                request.counterpartySharkId(),
                request.geoCountry(),
                request.phase().wire(),
                request.transactionType().wire());
    }

    private static com.sharkpay.risk.domain.EvaluationRequest toDomain(PersistedShapes.EvaluationRequest persisted) {
        return new com.sharkpay.risk.domain.EvaluationRequest(
                persisted.evaluationId(),
                persisted.transactionId(),
                persisted.subjectPrincipalId(),
                com.sharkpay.risk.domain.WireValue.parse(com.sharkpay.risk.domain.PrincipalType.class,
                        persisted.principalType(), "principal_type"),
                com.sharkpay.risk.domain.WireValue.parse(com.sharkpay.risk.domain.KycTier.class,
                        persisted.kycTier(), "kyc_tier"),
                com.sharkpay.money.Money.of(persisted.amount().amountMinor(), persisted.amount().currency()),
                com.sharkpay.risk.domain.WireValue.parse(com.sharkpay.risk.domain.Channel.class,
                        persisted.channel(), "channel"),
                persisted.counterpartySharkId(),
                persisted.geoCountry(),
                com.sharkpay.risk.domain.WireValue.parse(com.sharkpay.risk.domain.Phase.class,
                        persisted.phase(), "phase"),
                com.sharkpay.risk.domain.WireValue.parse(com.sharkpay.risk.domain.TransactionType.class,
                        persisted.transactionType(), "transaction_type"));
    }
}
