package com.sharkpay.risk.fakes;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.ports.CaseRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory case repository (stores the aggregate by reference). */
public final class InMemoryCaseRepository implements CaseRepository {

    private final Map<UUID, Case> store = new LinkedHashMap<>();

    @Override
    public Case save(Case caseEntity) {
        store.put(caseEntity.id(), caseEntity);
        return caseEntity;
    }

    @Override
    public Optional<Case> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    public int size() {
        return store.size();
    }

    /** All stored cases in insertion order (test assertions). */
    public List<Case> all() {
        return List.copyOf(store.values());
    }
}
