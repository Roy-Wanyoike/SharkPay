package com.sharkpay.risk.domain;

import com.sharkpay.risk.domain.exceptions.IllegalCaseTransitionException;
import com.sharkpay.risk.domain.exceptions.InvalidCaseIdException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-02T09:30:00Z");

    @Test
    void openCreatesOpenCaseWithPublicId() {
        Case c = Case.open(UUID.randomUUID(), "subject-1", "manual review", T0);
        assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.publicId()).matches("^case_[0-9a-f]{32}$");
        assertThat(c.assignedTo()).isNull();
        assertThat(c.createdAt()).isEqualTo(T0);
        assertThat(c.updatedAt()).isEqualTo(T0);
        assertThat(c.transitions()).isEmpty();
    }

    @Test
    void openRejectsBlankFields() {
        assertThatThrownBy(() -> Case.open(UUID.randomUUID(), " ", "reason", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Case.open(UUID.randomUUID(), "s", null, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legalChainRecordsActorsAndResolution() {
        Case c = Case.open(UUID.randomUUID(), "subject-1", "velocity spike", T0);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        c.transitionTo(CaseStatus.ESCALATED, "op-2", null, T2);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T2.plusSeconds(60));
        c.transitionTo(CaseStatus.CLOSED, "op-3", CaseResolution.SAR_FILED, T2.plusSeconds(120));

        assertThat(c.status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(c.updatedAt()).isEqualTo(T2.plusSeconds(120));
        List<CaseTransition> log = c.transitions();
        assertThat(log).hasSize(4);
        assertThat(log.get(0).actor()).isEqualTo("op-1");
        assertThat(log.get(0).from()).isEqualTo(CaseStatus.OPEN);
        assertThat(log.get(0).to()).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(log.get(0).resolution()).isNull();
        assertThat(log.get(3).actor()).isEqualTo("op-3");
        assertThat(log.get(3).resolution()).isEqualTo(CaseResolution.SAR_FILED);
        assertThat(log.get(3).occurredAt()).isEqualTo(T2.plusSeconds(120));
    }

    @Test
    void closedIsTerminal() {
        Case c = closedCase();
        assertThat(CaseStatus.CLOSED.terminal()).isTrue();
        assertThatThrownBy(() -> c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1))
                .isInstanceOf(IllegalCaseTransitionException.class)
                .hasMessageContaining("CLOSED is terminal");
    }

    @Test
    void illegalTransitionsAreRejected() {
        Case open = Case.open(UUID.randomUUID(), "s", "r", T0);
        assertThatThrownBy(() -> open.transitionTo(CaseStatus.CLOSED, "op-1", CaseResolution.CLEARED, T1))
                .isInstanceOf(IllegalCaseTransitionException.class);
        assertThatThrownBy(() -> open.transitionTo(CaseStatus.ESCALATED, "op-1", null, T1))
                .isInstanceOf(IllegalCaseTransitionException.class);

        Case underReview = Case.open(UUID.randomUUID(), "s", "r", T0);
        underReview.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        assertThatThrownBy(() -> underReview.transitionTo(CaseStatus.OPEN, "op-1", null, T2))
                .isInstanceOf(IllegalCaseTransitionException.class);
    }

    @Test
    void transitionValidationRules() {
        Case open = Case.open(UUID.randomUUID(), "s", "r", T0);
        assertThatThrownBy(() -> open.transitionTo(CaseStatus.UNDER_REVIEW, " ", null, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
        assertThatThrownBy(() -> open.transitionTo(null, "op-1", null, T1))
                .isInstanceOf(NullPointerException.class);

        Case review = Case.open(UUID.randomUUID(), "s", "r", T0);
        review.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        assertThatThrownBy(() -> review.transitionTo(CaseStatus.CLOSED, "op-1", null, T2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolution is required");
        assertThatThrownBy(() -> review.transitionTo(CaseStatus.ESCALATED, "op-1", CaseResolution.CLEARED, T2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolution is only allowed");
    }

    @Test
    void restorePreservesStateAndLog() {
        UUID id = UUID.randomUUID();
        Case original = Case.open(id, "subject-9", "reason", T0);
        original.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        Case restored = Case.restore(id, "subject-9", "reason", CaseStatus.UNDER_REVIEW,
                "op-9", T0, T1, List.copyOf(original.transitions()));
        assertThat(restored.status()).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(restored.assignedTo()).isEqualTo("op-9");
        assertThat(restored.transitions()).hasSize(1);
        assertThat(restored.publicId()).isEqualTo(original.publicId());
    }

    @Test
    void transitionIdsAreStableForIdempotentPersistence() {
        Case c = Case.open(UUID.randomUUID(), "s", "r", T0);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        UUID first = c.transitions().get(0).id();
        assertThat(first).isNotNull();
        assertThat(c.transitions().get(0).id()).isEqualTo(first);
    }

    @Test
    void caseTransitionRejectsBlankActor() {
        assertThatThrownBy(() -> new CaseTransition(null, CaseStatus.OPEN, CaseStatus.UNDER_REVIEW,
                "  ", null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void caseIdsParseEveryAcceptedForm() {
        UUID id = UUID.randomUUID();
        assertThat(CaseIds.parse(CaseIds.publicId(id))).isEqualTo(id);
        assertThat(CaseIds.parse(id.toString())).isEqualTo(id);
        assertThat(CaseIds.parse(id.toString().replace("-", ""))).isEqualTo(id);
        assertThat(CaseIds.parse(CaseIds.publicId(id).toUpperCase())).isEqualTo(id);
        assertThatThrownBy(() -> CaseIds.parse("case_zzz"))
                .isInstanceOf(InvalidCaseIdException.class);
        assertThatThrownBy(() -> CaseIds.parse("garbage"))
                .isInstanceOf(InvalidCaseIdException.class);
        assertThatThrownBy(() -> CaseIds.parse(""))
                .isInstanceOf(InvalidCaseIdException.class);
    }

    @Test
    void caseStatusWireAndEdges() {
        assertThat(CaseStatus.OPEN.wire()).isEqualTo("open");
        assertThat(CaseStatus.UNDER_REVIEW.wire()).isEqualTo("under_review");
        assertThat(CaseStatus.CLOSED.wire()).isEqualTo("closed");
        assertThat(CaseStatus.ESCALATED.wire()).isEqualTo("escalated");
        assertThat(CaseStatus.fromWire("under_review")).contains(CaseStatus.UNDER_REVIEW);
        assertThat(CaseStatus.fromWire("bogus")).isEmpty();

        assertThat(CaseStatus.OPEN.legalTargets()).containsExactly(CaseStatus.UNDER_REVIEW);
        assertThat(CaseStatus.UNDER_REVIEW.legalTargets())
                .containsExactlyInAnyOrder(CaseStatus.CLOSED, CaseStatus.ESCALATED);
        assertThat(CaseStatus.ESCALATED.legalTargets()).containsExactly(CaseStatus.UNDER_REVIEW);
        assertThat(CaseStatus.CLOSED.legalTargets()).isEmpty();
        assertThat(CaseStatus.OPEN.canTransitionTo(CaseStatus.UNDER_REVIEW)).isTrue();
        assertThat(CaseStatus.OPEN.canTransitionTo(CaseStatus.CLOSED)).isFalse();
    }

    @Test
    void wireValueParsesDomainEnums() {
        assertThat(WireValue.parse(KycTier.class, " full ", "kyc_tier")).isEqualTo(KycTier.FULL);
        assertThat(WireValue.parse(Channel.class, "PAYOUT", "channel")).isEqualTo(Channel.PAYOUT);
        assertThat(WireValue.parse(CaseResolution.class, "sar_filed", "resolution"))
                .isEqualTo(CaseResolution.SAR_FILED);
        assertThatThrownBy(() -> WireValue.parse(KycTier.class, "nope", "kyc_tier"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kyc_tier");
        assertThatThrownBy(() -> WireValue.parse(KycTier.class, "", "kyc_tier"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Case closedCase() {
        Case c = Case.open(UUID.randomUUID(), "s", "r", T0);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        c.transitionTo(CaseStatus.CLOSED, "op-2", CaseResolution.CLEARED, T2);
        return c;
    }
}
