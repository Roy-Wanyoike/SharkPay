package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.exceptions.InvalidEvaluationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationRequestTest {

    private static final String ID = "1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00";

    private static EvaluationRequest request() {
        return EvaluationRequest.of(ID, "subject-1", PrincipalType.INDIVIDUAL, KycTier.LIMITED,
                Money.of(100_00L, "KES"), Channel.PAYMENT);
    }

    @Test
    void defaultsPhasePreAndDerivesTransactionTypeFromChannel() {
        EvaluationRequest request = request();
        assertThat(request.phase()).isEqualTo(Phase.PRE);
        assertThat(request.transactionType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(request.transactionId()).isEqualTo(request.evaluationId());
        assertThat(request.evaluationId()).isEqualTo(ID);
        assertThat(request.counterpartySharkId()).isNull();
        assertThat(request.geoCountry()).isNull();
    }

    @Test
    void channelDefaultsForWalletAndFxAreDocumented() {
        assertThat(Channel.WALLET.defaultTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(Channel.FX.defaultTransactionType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(Channel.TRANSFER.defaultTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(Channel.PAYOUT.defaultTransactionType()).isEqualTo(TransactionType.PAYOUT);
    }

    @Test
    void withersApplyOptionalFields() {
        EvaluationRequest request = request()
                .withGeo("ke")
                .withCounterparty("shark_123")
                .withPhase(Phase.POST)
                .withTransactionId("pay_01HZX");
        assertThat(request.geoCountry()).isEqualTo("KE");       // normalized uppercase
        assertThat(request.counterpartySharkId()).isEqualTo("shark_123");
        assertThat(request.phase()).isEqualTo(Phase.POST);
        assertThat(request.transactionId()).isEqualTo("pay_01HZX");
    }

    @Test
    void equalRequestsAreValueEqualForIdempotencyConflicts() {
        EvaluationRequest first = request();
        EvaluationRequest second = new EvaluationRequest(ID, null, "subject-1",
                PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"),
                Channel.PAYMENT, null, null, null, null);
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(request().withGeo("KE"));
    }

    @Test
    void rejectsNonUuidEvaluationId() {
        assertThatThrownBy(() -> EvaluationRequest.of("not-a-uuid", "s", PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(1, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("evaluation_id must be a UUID");
    }

    @Test
    void rejectsBlankFields() {
        assertThatThrownBy(() -> EvaluationRequest.of("  ", "s", PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(1, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("evaluation_id must not be blank");
        assertThatThrownBy(() -> EvaluationRequest.of(ID, null, PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(1, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("subject_principal_id");
    }

    @Test
    void rejectsMissingEnums() {
        assertThatThrownBy(() -> EvaluationRequest.of(ID, "s", null, KycTier.LIMITED,
                Money.of(1, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("principal_type");
        assertThatThrownBy(() -> EvaluationRequest.of(ID, "s", PrincipalType.INDIVIDUAL, null,
                Money.of(1, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("kyc_tier");
        assertThatThrownBy(() -> EvaluationRequest.of(ID, "s", PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(1, "KES"), null))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("channel");
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> EvaluationRequest.of(ID, "s", PrincipalType.INDIVIDUAL,
                KycTier.LIMITED, Money.of(0, "KES"), Channel.PAYMENT))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("amount must be positive");
    }

    @Test
    void rejectsInvalidGeoCountry() {
        assertThatThrownBy(() -> request().withGeo("KEN"))
                .isInstanceOf(InvalidEvaluationException.class)
                .hasMessageContaining("ISO 3166-1 alpha-2");
        assertThatThrownBy(() -> request().withGeo("1A"))
                .isInstanceOf(InvalidEvaluationException.class);
    }

    @Test
    void blankOptionalsNormalizeToNull() {
        EvaluationRequest request = request().withCounterparty("   ").withGeo("");
        assertThat(request.counterpartySharkId()).isNull();
        assertThat(request.geoCountry()).isNull();
    }

    @Test
    void toStringCarriesTheWireVocabulary() {
        // wire vocabulary is lowercase by design (WireValue): phase=post, channel=payment
        assertThat(request().withPhase(Phase.POST).toString())
                .contains(ID)
                .contains("phase=post")
                .contains("channel=payment");
        assertThat(UUID.fromString(ID)).isNotNull(); // sanity: the fixture is a UUID
        assertThat(Instant.parse("2026-09-01T10:00:00Z")).isNotNull();
    }
}
