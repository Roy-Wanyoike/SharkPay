package com.sharkpay.reconciliation.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * JPA entity for the {@code recon_runs} table: one comparison run.
 */
@Entity
@Table(name = "recon_runs")
public class ReconRunEntity {

    @Id
    @Column(name = "id", nullable = false, length = 40)
    private String id;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "window_from", nullable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false)
    private Instant windowTo;

    @Column(name = "state", nullable = false, length = 12)
    private String state;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "provider_lines", nullable = false)
    private int providerLines;

    @Column(name = "internal_lines", nullable = false)
    private int internalLines;

    @Column(name = "matched_pairs", nullable = false)
    private int matchedPairs;

    @Column(name = "break_count", nullable = false)
    private int breakCount;

    protected ReconRunEntity() {
    }

    public ReconRunEntity(String id, String provider, Instant windowFrom, Instant windowTo,
                          String state, Instant startedAt, Instant completedAt, String failureReason,
                          int providerLines, int internalLines, int matchedPairs, int breakCount) {
        this.id = id;
        this.provider = provider;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.state = state;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.failureReason = failureReason;
        this.providerLines = providerLines;
        this.internalLines = internalLines;
        this.matchedPairs = matchedPairs;
        this.breakCount = breakCount;
    }

    /** Maps the domain run onto a fresh entity (insert shape). */
    public static ReconRunEntity fromDomain(com.sharkpay.reconciliation.domain.ReconRun run) {
        return new ReconRunEntity(run.id(), run.provider(), run.window().from(), run.window().to(),
                run.state().wireName(), run.startedAt(), run.completedAt(), run.failureReason(),
                run.providerLines(), run.internalLines(), run.matchedPairs(), run.breakCount());
    }

    /** Applies the mutable fields of the domain run onto this entity (update shape). */
    public void applyDomain(com.sharkpay.reconciliation.domain.ReconRun run) {
        this.state = run.state().wireName();
        this.completedAt = run.completedAt();
        this.failureReason = run.failureReason();
        this.providerLines = run.providerLines();
        this.internalLines = run.internalLines();
        this.matchedPairs = run.matchedPairs();
        this.breakCount = run.breakCount();
    }

    /** Restores the domain aggregate. */
    public com.sharkpay.reconciliation.domain.ReconRun toDomain() {
        return com.sharkpay.reconciliation.domain.ReconRun.rehydrate(id, provider,
                new com.sharkpay.reconciliation.domain.ReconWindow(windowFrom, windowTo), startedAt,
                com.sharkpay.reconciliation.domain.ReconRunState.fromWireName(state), completedAt,
                failureReason, providerLines, internalLines, matchedPairs, breakCount);
    }

    public String getId() {
        return id;
    }
}
