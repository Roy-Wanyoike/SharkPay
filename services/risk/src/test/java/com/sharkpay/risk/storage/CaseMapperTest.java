package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseMapperTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-02T09:30:00Z");

    @Test
    void freshCaseMapsToItsRowAndEmptyLog() {
        Case c = Case.open(UUID.randomUUID(), "subject-1", "reason", T0);

        CaseEntity entity = CaseMapper.toEntity(c);
        List<CaseTransitionEntity> transitions = CaseMapper.toTransitionEntities(c);

        assertThat(entity.id).isEqualTo(c.id());
        assertThat(entity.subjectPrincipalId).isEqualTo("subject-1");
        assertThat(entity.reason).isEqualTo("reason");
        assertThat(entity.status).isEqualTo(CaseStatus.OPEN);
        assertThat(entity.assignedTo).isNull();
        assertThat(entity.createdAt).isEqualTo(T0);
        assertThat(entity.updatedAt).isEqualTo(T0);
        assertThat(transitions).isEmpty();
    }

    @Test
    void transitionedCaseRoundTripsWithItsOrderedLog() {
        Case original = Case.open(UUID.randomUUID(), "subject-2", "velocity spike", T0);
        original.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);
        original.transitionTo(CaseStatus.ESCALATED, "op-2", null, T2);
        original.transitionTo(CaseStatus.UNDER_REVIEW, "op-2", null, T2.plusSeconds(60));
        original.transitionTo(CaseStatus.CLOSED, "op-3", CaseResolution.SAR_FILED, T2.plusSeconds(120));

        CaseEntity entity = CaseMapper.toEntity(original);
        List<CaseTransitionEntity> transitionEntities = CaseMapper.toTransitionEntities(original);

        assertThat(entity.status).isEqualTo(CaseStatus.CLOSED);
        assertThat(entity.updatedAt).isEqualTo(T2.plusSeconds(120));
        assertThat(transitionEntities).hasSize(4);
        assertThat(transitionEntities.get(0).id).isEqualTo(original.transitions().get(0).id());
        assertThat(transitionEntities.get(0).caseId).isEqualTo(original.id());
        assertThat(transitionEntities.get(0).fromStatus).isEqualTo(CaseStatus.OPEN);
        assertThat(transitionEntities.get(0).toStatus).isEqualTo(CaseStatus.UNDER_REVIEW);
        assertThat(transitionEntities.get(0).actor).isEqualTo("op-1");
        assertThat(transitionEntities.get(0).resolution).isNull();
        assertThat(transitionEntities.get(3).resolution).isEqualTo(CaseResolution.SAR_FILED);

        Case restored = CaseMapper.toDomain(entity, transitionEntities);
        assertThat(restored.status()).isEqualTo(original.status());
        assertThat(restored.publicId()).isEqualTo(original.publicId());
        assertThat(restored.subjectPrincipalId()).isEqualTo("subject-2");
        assertThat(restored.createdAt()).isEqualTo(T0);
        assertThat(restored.updatedAt()).isEqualTo(original.updatedAt());
        assertThat(restored.transitions()).hasSize(4);
        assertThat(restored.transitions().get(2).actor()).isEqualTo("op-2");
        assertThat(restored.transitions().get(3).resolution()).isEqualTo(CaseResolution.SAR_FILED);
        assertThat(restored.transitions().get(3).id()).isEqualTo(original.transitions().get(3).id());
    }

    @Test
    void restoredCaseKeepsWorkingAsAnAggregate() {
        Case original = Case.open(UUID.randomUUID(), "s", "r", T0);
        original.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T1);

        Case restored = CaseMapper.toDomain(CaseMapper.toEntity(original),
                CaseMapper.toTransitionEntities(original));
        restored.transitionTo(CaseStatus.CLOSED, "op-9", CaseResolution.CLEARED, T2);

        assertThat(restored.status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(restored.transitions()).hasSize(2);
        assertThat(restored.transitions().get(1).from()).isEqualTo(CaseStatus.UNDER_REVIEW);
    }
}
