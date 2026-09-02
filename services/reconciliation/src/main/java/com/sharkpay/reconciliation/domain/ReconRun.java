package com.sharkpay.reconciliation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One reconciliation run: a provider, a half-open window
 * {@code [from, to)}, and the outcome of comparing the provider statement
 * against the internal ledger postings inside that window. Lifecycle:
 * {@code RUNNING → COMPLETED | FAILED} — a run executes synchronously and
 * terminal states are immutable.
 *
 * <p>Line counts: {@code providerLines}/{@code internalLines} are the sizes
 * of the fetched sides, {@code matchedPairs} the references that agreed to
 * meet, and {@code breakCount} the discrepancies recorded (a matched pair
 * can yield several breaks).</p>
 */
public final class ReconRun {

    private final String id;
    private final String provider;
    private final ReconWindow window;
    private final Instant startedAt;
    private ReconRunState state;
    private Instant completedAt;
    private String failureReason;
    private int providerLines;
    private int internalLines;
    private int matchedPairs;
    private int breakCount;

    private ReconRun(String id, String provider, ReconWindow window, Instant startedAt,
                     ReconRunState state, Instant completedAt, String failureReason,
                     int providerLines, int internalLines, int matchedPairs, int breakCount) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.provider = Objects.requireNonNull(provider, "provider is required");
        if (provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.window = Objects.requireNonNull(window, "window is required");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt is required");
        this.state = Objects.requireNonNull(state, "state is required");
        this.completedAt = completedAt;
        this.failureReason = failureReason;
        this.providerLines = providerLines;
        this.internalLines = internalLines;
        this.matchedPairs = matchedPairs;
        this.breakCount = breakCount;
    }

    /** Starts a new run (RUNNING). */
    public static ReconRun start(String id, String provider, ReconWindow window, Instant now) {
        return new ReconRun(id, provider, window, now, ReconRunState.RUNNING, null, null, 0, 0, 0, 0);
    }

    /** Rehydrates a run from storage (all fields, no state guards). */
    public static ReconRun rehydrate(String id, String provider, ReconWindow window, Instant startedAt,
                                     ReconRunState state, Instant completedAt, String failureReason,
                                     int providerLines, int internalLines, int matchedPairs,
                                     int breakCount) {
        return new ReconRun(id, provider, window, startedAt, state, completedAt, failureReason,
                providerLines, internalLines, matchedPairs, breakCount);
    }

    /** Terminal transition: the comparison executed and breaks were recorded. */
    public void complete(Counts counts, Instant now) {
        assertRunning("complete");
        Objects.requireNonNull(counts, "counts is required");
        Objects.requireNonNull(now, "now is required");
        this.providerLines = counts.providerLines();
        this.internalLines = counts.internalLines();
        this.matchedPairs = counts.matchedPairs();
        this.breakCount = counts.breakCount();
        this.state = ReconRunState.COMPLETED;
        this.completedAt = now;
    }

    /** Terminal transition: a statement side could not be fetched. */
    public void fail(String reason, Instant now) {
        assertRunning("fail");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("failure reason must not be blank");
        }
        this.failureReason = reason;
        this.state = ReconRunState.FAILED;
        this.completedAt = now;
    }

    private void assertRunning(String operation) {
        if (state.isTerminal()) {
            throw new ReconciliationStateException(
                    "recon run " + id + " is already " + state.wireName() + "; cannot " + operation);
        }
    }

    public String id() {
        return id;
    }

    public String provider() {
        return provider;
    }

    public ReconWindow window() {
        return window;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public ReconRunState state() {
        return state;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String failureReason() {
        return failureReason;
    }

    public int providerLines() {
        return providerLines;
    }

    public int internalLines() {
        return internalLines;
    }

    public int matchedPairs() {
        return matchedPairs;
    }

    public int breakCount() {
        return breakCount;
    }

    /** Line counts recorded when a run completes. */
    public record Counts(int providerLines, int internalLines, int matchedPairs, int breakCount) {
    }
}
