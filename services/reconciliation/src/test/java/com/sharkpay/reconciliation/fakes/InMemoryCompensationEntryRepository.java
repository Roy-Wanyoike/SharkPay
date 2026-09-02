package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory compensation-entry repository (src/test fake, ADR 003 §3):
 * insertion order is proposal order; saves are id-keyed upserts that keep
 * the original position (execution mutations persist in place). Mirrors
 * the JPA adapter's semantics so the key-sequencing rule
 * ({@code ops:adj:<breakId>[#n]} from {@code countByBreak}) is exercised
 * exactly as in production.
 */
public final class InMemoryCompensationEntryRepository implements CompensationEntryRepository {

    private final Map<String, CompensationEntry> entries = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void save(CompensationEntry entry) {
        lock.lock();
        try {
            entries.put(entry.id(), entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<CompensationEntry> findById(String id) {
        lock.lock();
        try {
            return Optional.ofNullable(entries.get(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<CompensationEntry> listByBreak(String breakId) {
        lock.lock();
        try {
            return snapshot().stream().filter(entry -> entry.breakId().equals(breakId)).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long countByBreak(String breakId) {
        lock.lock();
        try {
            return snapshot().stream().filter(entry -> entry.breakId().equals(breakId)).count();
        } finally {
            lock.unlock();
        }
    }

    private List<CompensationEntry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    public int count() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }
}
