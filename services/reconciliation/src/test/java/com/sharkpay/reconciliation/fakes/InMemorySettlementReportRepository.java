package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.ports.SettlementReportRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory settlement-report repository (src/test fake, ADR 003 §3),
 * mirroring the JPA adapter: one report per completed run, the
 * (provider, exact window) lookup returns the NEWEST report for that
 * window, and the per-provider list is newest first.
 */
public final class InMemorySettlementReportRepository implements SettlementReportRepository {

    private final Map<String, SettlementReport> reports = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public void save(SettlementReport report) {
        lock.lock();
        try {
            reports.put(report.id(), report);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<SettlementReport> findByRunId(String runId) {
        lock.lock();
        try {
            return snapshot().stream()
                    .filter(report -> report.runId().equals(runId))
                    .findFirst();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<SettlementReport> findByProviderAndWindow(String provider,
                                                               java.time.Instant from,
                                                               java.time.Instant to) {
        lock.lock();
        try {
            return snapshot().stream()
                    .filter(report -> report.provider().equals(provider)
                            && report.window().from().equals(from)
                            && report.window().to().equals(to))
                    .max(Comparator.comparing(SettlementReport::generatedAt)
                            .thenComparing(SettlementReport::id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<SettlementReport> listByProvider(String provider) {
        lock.lock();
        try {
            return snapshot().stream()
                    .filter(report -> report.provider().equals(provider))
                    .sorted(Comparator.comparing(SettlementReport::generatedAt).reversed()
                            .thenComparing(SettlementReport::id, Comparator.reverseOrder()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    private List<SettlementReport> snapshot() {
        return new ArrayList<>(reports.values());
    }

    public int count() {
        lock.lock();
        try {
            return reports.size();
        } finally {
            lock.unlock();
        }
    }
}
