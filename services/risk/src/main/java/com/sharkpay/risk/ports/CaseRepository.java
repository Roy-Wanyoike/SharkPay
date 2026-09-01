package com.sharkpay.risk.ports;

import com.sharkpay.risk.domain.Case;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for compliance cases (id = internal UUID key). */
public interface CaseRepository {

    Case save(Case caseEntity);

    Optional<Case> findById(UUID id);
}
