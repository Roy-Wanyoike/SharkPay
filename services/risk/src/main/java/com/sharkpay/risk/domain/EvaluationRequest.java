package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.exceptions.InvalidEvaluationException;

import java.util.UUID;

/**
 * A single risk evaluation request.
 *
 * <ul>
 *   <li>{@code evaluationId} — the caller-supplied idempotency key (UUID). It
 *       becomes the primary key of the stored evaluation: replaying the same
 *       id returns the original decision without re-running rules or bumping
 *       velocity counters.</li>
 *   <li>{@code transactionId} — the payment/payout/transfer intent id under
 *       evaluation (event payload {@code transaction_id}); defaults to the
 *       evaluation id until the orchestrators wire up in Wave 3.</li>
 *   <li>{@code phase} — pre- or post-authorization (defaults to {@code PRE}).</li>
 *   <li>{@code transactionType} — event payload vocabulary; defaults to the
 *       channel's documented mapping when absent.</li>
 * </ul>
 *
 * The canonical constructor validates and normalizes, so two logically equal
 * requests are {@code equals}-equal — the basis of idempotency conflict
 * detection. Money is always {@link Money} (never double/float).
 */
public record EvaluationRequest(
        String evaluationId,
        String transactionId,
        String subjectPrincipalId,
        PrincipalType principalType,
        KycTier kycTier,
        Money amount,
        Channel channel,
        String counterpartySharkId,
        String geoCountry,
        Phase phase,
        TransactionType transactionType) {

    public EvaluationRequest {
        evaluationId = normalizeUuid(evaluationId, "evaluation_id");
        transactionId = (transactionId == null || transactionId.isBlank())
                ? evaluationId
                : transactionId.trim();
        subjectPrincipalId = requireText(subjectPrincipalId, "subject_principal_id");
        if (principalType == null) {
            throw new InvalidEvaluationException("principal_type must not be null");
        }
        if (kycTier == null) {
            throw new InvalidEvaluationException("kyc_tier must not be null");
        }
        if (amount == null) {
            throw new InvalidEvaluationException("amount must not be null");
        }
        if (!amount.isPositive()) {
            throw new InvalidEvaluationException("amount must be positive (got " + amount + ")");
        }
        if (channel == null) {
            throw new InvalidEvaluationException("channel must not be null");
        }
        counterpartySharkId = trimToNull(counterpartySharkId);
        geoCountry = normalizeGeo(geoCountry);
        phase = phase == null ? Phase.PRE : phase;
        transactionType = transactionType == null ? channel.defaultTransactionType() : transactionType;
    }

    /** Minimal factory; optional fields default via the canonical constructor. */
    public static EvaluationRequest of(String evaluationId,
                                       String subjectPrincipalId,
                                       PrincipalType principalType,
                                       KycTier kycTier,
                                       Money amount,
                                       Channel channel) {
        return new EvaluationRequest(evaluationId, null, subjectPrincipalId,
                principalType, kycTier, amount, channel, null, null, null, null);
    }

    public EvaluationRequest withCounterparty(String counterpartySharkId) {
        return new EvaluationRequest(evaluationId, transactionId, subjectPrincipalId, principalType, kycTier,
                amount, channel, counterpartySharkId, geoCountry, phase, transactionType);
    }

    public EvaluationRequest withGeo(String geoCountry) {
        return new EvaluationRequest(evaluationId, transactionId, subjectPrincipalId, principalType, kycTier,
                amount, channel, counterpartySharkId, geoCountry, phase, transactionType);
    }

    public EvaluationRequest withPhase(Phase newPhase) {
        return new EvaluationRequest(evaluationId, transactionId, subjectPrincipalId, principalType, kycTier,
                amount, channel, counterpartySharkId, geoCountry, newPhase, transactionType);
    }

    public EvaluationRequest withTransactionId(String newTransactionId) {
        return new EvaluationRequest(evaluationId, newTransactionId, subjectPrincipalId, principalType, kycTier,
                amount, channel, counterpartySharkId, geoCountry, phase, transactionType);
    }

    private static String normalizeUuid(String raw, String field) {
        String trimmed = requireText(raw, field);
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException e) {
            throw new InvalidEvaluationException(field + " must be a UUID, got '" + trimmed + "'");
        }
    }

    private static String requireText(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidEvaluationException(field + " must not be blank");
        }
        return raw.trim();
    }

    private static String trimToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    private static String normalizeGeo(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!upper.matches("[A-Z]{2}")) {
            throw new InvalidEvaluationException(
                    "geo_country must be an ISO 3166-1 alpha-2 code, got '" + raw + "'");
        }
        return upper;
    }
}
