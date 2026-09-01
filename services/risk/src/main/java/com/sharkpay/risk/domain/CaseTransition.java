package com.sharkpay.risk.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded case-state transition (4-eyes: {@code actor} is the operator
 * id that performed it, docs/SECURITY.md 4). The id is assigned on creation
 * and kept stable so persistence can save transitions idempotently.
 */
public record CaseTransition(
        UUID id,
        CaseStatus from,
        CaseStatus to,
        String actor,
        CaseResolution resolution,
        Instant occurredAt) {

    public CaseTransition {
        id = (id == null) ? UUID.randomUUID() : id;
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
