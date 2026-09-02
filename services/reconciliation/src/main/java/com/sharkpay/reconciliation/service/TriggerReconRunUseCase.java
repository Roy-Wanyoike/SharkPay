package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.ComparisonEngine;
import com.sharkpay.reconciliation.domain.DetectedBreak;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.domain.ReconWindow;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.domain.StatementUnavailableException;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.ports.EventPublisher;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import com.sharkpay.reconciliation.ports.LedgerStatementPort;
import com.sharkpay.reconciliation.ports.ProviderStatementPort;
import com.sharkpay.reconciliation.ports.Randomness;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;
import com.sharkpay.reconciliation.ports.ReconRunRepository;
import com.sharkpay.reconciliation.ports.SettlementReportRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Trigger a recon run (WP-10's outer consistency loop, BACKEND-DESIGN §4):
 * fetch both statement sides of the half-open window, compare them with the
 * pure {@link ComparisonEngine}, record every classified break, persist the
 * settlement report and complete the run. Idempotent on the
 * Idempotency-Key: a replay with the same payload returns the original run
 * (and its breaks) with no second effect; a replay with a different
 * payload is a 409 conflict.
 *
 * <p>Failure discipline: an unavailable statement side (provider breaker
 * open, ledger unreachable) is an <i>expected</i> outcome — the run is
 * terminal-FAILED with the reason recorded (auditable), the API still
 * returns the run. A FAILED run keeps its idempotency binding: the same key
 * deterministically replays the same failed run; a retry is a new key.</p>
 */
public final class TriggerReconRunUseCase {

    private final ProviderStatementPort providers;
    private final LedgerStatementPort ledger;
    private final ReconRunRepository runs;
    private final ReconBreakRepository breaks;
    private final SettlementReportRepository reports;
    private final IdempotencyStore idempotency;
    private final EventPublisher events;
    private final ReconEvents eventFactory;
    private final Randomness randomness;
    private final Clock clock;

    public TriggerReconRunUseCase(ProviderStatementPort providers, LedgerStatementPort ledger,
                                  ReconRunRepository runs, ReconBreakRepository breaks,
                                  SettlementReportRepository reports, IdempotencyStore idempotency,
                                  EventPublisher events, ReconEvents eventFactory,
                                  Randomness randomness, Clock clock) {
        this.providers = Objects.requireNonNull(providers, "providerStatementPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerStatementPort is required");
        this.runs = Objects.requireNonNull(runs, "reconRunRepository is required");
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.reports = Objects.requireNonNull(reports, "settlementReportRepository is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required, non-blank)
     * @param provider       provider name (e.g. {@code honeycoin})
     * @param from           window start (inclusive)
     * @param to             window end (exclusive, strictly after from)
     */
    public Result trigger(String idempotencyKey, String provider, Instant from, Instant to) {
        requireKey(idempotencyKey);
        Objects.requireNonNull(from, "from is required");
        Objects.requireNonNull(to, "to is required");
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        String canonicalProvider = provider.trim();
        ReconWindow window = new ReconWindow(from, to);
        String fingerprint = fingerprint(canonicalProvider, window);

        java.util.Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.TRIGGER_RUN, idempotencyKey.trim());
        if (stored.isPresent()) {
            return replay(stored.get(), idempotencyKey, fingerprint);
        }

        Instant now = clock.instant();
        ReconRun run = ReconRun.start(randomness.runId(), canonicalProvider, window, now);
        runs.save(run);

        List<com.sharkpay.reconciliation.domain.ProviderStatementLine> providerLines;
        List<com.sharkpay.reconciliation.domain.InternalLedgerLine> internalLines;
        try {
            providerLines = providers.fetch(canonicalProvider, from, to);
            internalLines = ledger.internalLines(canonicalProvider, from, to);
        } catch (StatementUnavailableException unavailable) {
            run.fail(unavailable.getMessage(), clock.instant());
            runs.save(run);
            idempotency.put(IdempotencyStore.Scope.TRIGGER_RUN, idempotencyKey.trim(),
                    new IdempotencyStore.StoredRequest(fingerprint, run.id()));
            return new Result(run, List.of(), null, false);
        }

        ComparisonEngine.Result comparison = ComparisonEngine.compare(providerLines, internalLines);

        List<ReconBreak> recorded = new ArrayList<>(comparison.breakCount());
        for (DetectedBreak detected : comparison.breaks()) {
            ReconBreak break_ = ReconBreak.detect(randomness.breakId(), run.id(),
                    canonicalProvider, detected, now);
            breaks.save(break_);
            recorded.add(break_);
            events.publish(eventFactory.breakDetected(break_, now));
        }

        SettlementReport report = SettlementReport
                .from(randomness.settlementId(), run, providerLines, internalLines, clock.instant())
                .withBreaks(SettlementReport.BreakSummary.fromBreaks(comparison.breaks()));

        run.complete(new ReconRun.Counts(providerLines.size(), internalLines.size(),
                comparison.matchedPairs(), comparison.breakCount()), clock.instant());
        runs.save(run);
        reports.save(report);
        events.publish(eventFactory.runCompleted(run, clock.instant()));

        idempotency.put(IdempotencyStore.Scope.TRIGGER_RUN, idempotencyKey.trim(),
                new IdempotencyStore.StoredRequest(fingerprint, run.id()));
        return new Result(run, List.copyOf(recorded), report, false);
    }

    private Result replay(IdempotencyStore.StoredRequest request, String key, String fingerprint) {
        if (!request.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        ReconRun original = runs.findById(request.entityId())
                .orElseThrow(() -> new NoSuchElementException(
                        "recon run " + request.entityId() + " referenced by idempotency key "
                                + key + " is missing"));
        List<ReconBreak> recorded = breaks.listByRun(original.id());
        SettlementReport report = original.state() == com.sharkpay.reconciliation.domain.ReconRunState.COMPLETED
                ? reports.findByRunId(original.id()).orElse(null)
                : null;
        return new Result(original, recorded, report, true);
    }

    /** Canonical request fingerprint for conflict detection. */
    static String fingerprint(String provider, ReconWindow window) {
        return "TRIGGER_RUN|" + provider + "|" + window.canonical();
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        if (key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key header must be at most 128 characters");
        }
    }

    /**
     * @param run    the created (or replayed) run — may be FAILED when a
     *               statement side was unavailable
     * @param breaks the run's recorded breaks (empty for FAILED runs)
     * @param report the settlement report (null when the run FAILED)
     * @param replay true when served from the idempotency store
     */
    public record Result(ReconRun run, List<ReconBreak> breaks, SettlementReport report,
                         boolean replay) {
    }
}
