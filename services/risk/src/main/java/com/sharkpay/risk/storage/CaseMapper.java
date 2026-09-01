package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseTransition;

import java.util.List;

/**
 * Domain &lt;-&gt; entity mapping for cases: the case row plus its ordered
 * transition log. Transition ids are domain-assigned and stable, so saving
 * is idempotent (the adapter skips already-persisted transitions).
 */
public final class CaseMapper {

    private CaseMapper() {
    }

    public static CaseEntity toEntity(Case c) {
        return new CaseEntity(c.id(), c.subjectPrincipalId(), c.reason(), c.status(),
                c.assignedTo(), c.createdAt(), c.updatedAt());
    }

    public static List<CaseTransitionEntity> toTransitionEntities(Case c) {
        return c.transitions().stream()
                .map(t -> new CaseTransitionEntity(t.id(), c.id(), t.from(), t.to(), t.actor(),
                        t.resolution(), t.occurredAt()))
                .toList();
    }

    public static Case toDomain(CaseEntity entity, List<CaseTransitionEntity> transitions) {
        List<CaseTransition> log = transitions.stream()
                .map(t -> new CaseTransition(t.id, t.fromStatus, t.toStatus, t.actor, t.resolution, t.occurredAt))
                .toList();
        return Case.restore(entity.id, entity.subjectPrincipalId, entity.reason, entity.status,
                entity.assignedTo, entity.createdAt, entity.updatedAt, log);
    }
}
