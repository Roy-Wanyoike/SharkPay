package com.sharkpay.risk.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireEnumsTest {

    @Test
    void decisionWireValues() {
        assertThat(Decision.ALLOW.wire()).isEqualTo("allow");
        assertThat(Decision.DENY.wire()).isEqualTo("deny");
        assertThat(Decision.REVIEW.wire()).isEqualTo("review");
        assertThat(Decision.ALLOW.toString()).isEqualTo("allow");

        assertThat(Decision.fromWire("allow")).contains(Decision.ALLOW);
        assertThat(Decision.fromWire("deny")).contains(Decision.DENY);
        assertThat(Decision.fromWire("review")).contains(Decision.REVIEW);
        assertThat(Decision.fromWire("ALLOW")).isEmpty();
        assertThat(Decision.fromWire("nope")).isEmpty();
    }

    @Test
    void outcomeWireValues() {
        assertThat(Outcome.PASS.wire()).isEqualTo("pass");
        assertThat(Outcome.DENY.wire()).isEqualTo("deny");
        assertThat(Outcome.REVIEW.wire()).isEqualTo("review");
        assertThat(Outcome.REVIEW.toString()).isEqualTo("review");

        assertThat(Outcome.fromWire("pass")).contains(Outcome.PASS);
        assertThat(Outcome.fromWire("review")).contains(Outcome.REVIEW);
        assertThat(Outcome.fromWire("")).isEmpty();
        assertThat(Outcome.fromWire("PASS")).isEmpty();
    }

    @Test
    void caseResolutionWireValues() {
        assertThat(CaseResolution.CLEARED.wire()).isEqualTo("cleared");
        assertThat(CaseResolution.BLOCKED.wire()).isEqualTo("blocked");
        assertThat(CaseResolution.REVERSED.wire()).isEqualTo("reversed");
        assertThat(CaseResolution.SAR_FILED.wire()).isEqualTo("sar_filed");
        assertThat(CaseResolution.SAR_FILED.toString()).isEqualTo("sar_filed");

        assertThat(CaseResolution.fromWire("blocked")).contains(CaseResolution.BLOCKED);
        assertThat(CaseResolution.fromWire("junk")).isEmpty();
    }

    @Test
    void principalTypeAndKycTierWireValues() {
        assertThat(PrincipalType.INDIVIDUAL.wire()).isEqualTo("individual");
        assertThat(PrincipalType.BUSINESS.wire()).isEqualTo("business");
        assertThat(PrincipalType.AGENT.wire()).isEqualTo("agent");
        assertThat(PrincipalType.fromWire("agent")).contains(PrincipalType.AGENT);
        assertThat(PrincipalType.fromWire("Agent")).isEmpty();

        assertThat(KycTier.UNVERIFIED.wire()).isEqualTo("unverified");
        assertThat(KycTier.LIMITED.wire()).isEqualTo("limited");
        assertThat(KycTier.FULL.wire()).isEqualTo("full");
        assertThat(KycTier.fromWire("full")).contains(KycTier.FULL);
        assertThat(KycTier.fromWire("silver")).isEmpty();
    }

    @Test
    void phaseAndTransactionTypeWireValues() {
        assertThat(Phase.PRE.wire()).isEqualTo("pre");
        assertThat(Phase.POST.wire()).isEqualTo("post");
        assertThat(Phase.fromWire("post")).contains(Phase.POST);
        assertThat(Phase.fromWire("mid")).isEmpty();

        assertThat(TransactionType.PAYMENT.wire()).isEqualTo("payment");
        assertThat(TransactionType.PAYOUT.wire()).isEqualTo("payout");
        assertThat(TransactionType.TRANSFER.wire()).isEqualTo("transfer");
        assertThat(TransactionType.fromWire("transfer")).contains(TransactionType.TRANSFER);
        assertThat(TransactionType.fromWire("xfer")).isEmpty();
    }

    @Test
    void channelWireValuesAndDocumentedTypeDefaults() {
        assertThat(Channel.WALLET.wire()).isEqualTo("wallet");
        assertThat(Channel.PAYMENT.wire()).isEqualTo("payment");
        assertThat(Channel.PAYOUT.wire()).isEqualTo("payout");
        assertThat(Channel.TRANSFER.wire()).isEqualTo("transfer");
        assertThat(Channel.FX.wire()).isEqualTo("fx");

        assertThat(Channel.WALLET.defaultTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(Channel.FX.defaultTransactionType()).isEqualTo(TransactionType.PAYMENT);

        assertThat(Channel.fromWire("fx")).contains(Channel.FX);
        assertThat(Channel.fromWire("wallet")).contains(Channel.WALLET);
        assertThat(Channel.fromWire("unknown")).isEmpty();
    }

    @Test
    void caseTransitionAssignsAnIdWhenAbsentAndValidates() {
        Instant at = Instant.parse("2026-09-01T10:00:00Z");

        CaseTransition generated = new CaseTransition(null, CaseStatus.OPEN, CaseStatus.UNDER_REVIEW,
                "op-1", null, at);
        assertThat(generated.id()).isNotNull();

        UUID id = UUID.randomUUID();
        CaseTransition explicit = new CaseTransition(id, CaseStatus.OPEN, CaseStatus.UNDER_REVIEW,
                "op-1", null, at);
        assertThat(explicit.id()).isEqualTo(id);
        assertThat(explicit.from()).isEqualTo(CaseStatus.OPEN);
        assertThat(explicit.to()).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(explicit.actor()).isEqualTo("op-1");
        assertThat(explicit.occurredAt()).isEqualTo(at);

        assertThatThrownBy(() -> new CaseTransition(id, null, CaseStatus.UNDER_REVIEW, "op-1", null, at))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> new CaseTransition(id, CaseStatus.OPEN, null, "op-1", null, at))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to");
        assertThatThrownBy(() -> new CaseTransition(id, CaseStatus.OPEN, CaseStatus.UNDER_REVIEW,
                "op-1", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");
    }
}
