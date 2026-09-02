package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.ports.ReconRunRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory recon-run repository (src/test fake, ADR 003 §3), keyed by id;
 * list-by-provider orders newest first.
 */
public final class InMemoryReconRunRepository implements ReconRunRepository {

    private final Map<String, ReconRun> runs = new ConcurrentHashMap<>();

    @Override
    public void save(ReconRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public Optional<ReconRun> findById(String id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public List<ReconRun> listByProvider(String provider) {
        return runs.values().stream()
                .filter(run -> run.provider().equals(provider))
                .sorted(Comparator.comparing(ReconRun::startedAt, Comparator.reverseOrder())
                        .thenComparing(ReconRun::id, Comparator.reverseOrder()))
                .toList();
    }

    public int count() {
        return runs.size();
    }
}
