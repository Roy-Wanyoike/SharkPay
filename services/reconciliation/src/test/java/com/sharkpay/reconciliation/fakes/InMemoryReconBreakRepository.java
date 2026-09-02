package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory break repository (src/test fake, ADR 003 §3): insertion order
 * is detection order; saves are id-keyed upserts that keep the original
 * position (lifecycle mutations persist in place, history never
 * rewrites).
 */
public final class InMemoryReconBreakRepository implements ReconBreakRepository {

    private final Map<String, ReconBreak> breaks = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void save(ReconBreak break_) {
        lock.lock();
        try {
            breaks.put(break_.id(), break_);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<ReconBreak> findById(String id) {
        lock.lock();
        try {
            return Optional.ofNullable(breaks.get(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ReconBreak> listByRun(String runId) {
        lock.lock();
        try {
            return snapshot().stream().filter(b -> b.runId().equals(runId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ReconBreak> listByState(BreakState state) {
        lock.lock();
        try {
            return snapshot().stream().filter(b -> b.state() == state).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ReconBreak> listActive() {
        lock.lock();
        try {
            return snapshot().stream().filter(b -> b.state().isActive()).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<ReconBreak> listByProvider(String provider) {
        lock.lock();
        try {
            return snapshot().stream().filter(b -> b.provider().equals(provider)).toList();
        } finally {
            lock.unlock();
        }
    }

    private List<ReconBreak> snapshot() {
        return new ArrayList<>(breaks.values());
    }

    public int count() {
        lock.lock();
        try {
            return breaks.size();
        } finally {
            lock.unlock();
        }
    }
}
