package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.MoneyOverflowException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The settlement report of one recon run (per provider, per window): totals
 * and fees on both sides for every currency that appears, plus the break
 * summary. This is the daily finance report RB-7's symptoms point at
 * ("daily recon report non-zero").
 */
public final class SettlementReport {

    private final String id;
    private final String runId;
    private final String provider;
    private final ReconWindow window;
    private final Instant generatedAt;
    private final List<CurrencyLine> currencyLines;
    private final BreakSummary breakSummary;

    private SettlementReport(String id, String runId, String provider, ReconWindow window,
                             Instant generatedAt, List<CurrencyLine> currencyLines,
                             BreakSummary breakSummary) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.runId = Objects.requireNonNull(runId, "runId is required");
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.window = Objects.requireNonNull(window, "window is required");
        this.generatedAt = Objects.requireNonNull(generatedAt, "generatedAt is required");
        this.currencyLines = List.copyOf(currencyLines);
        this.breakSummary = Objects.requireNonNull(breakSummary, "breakSummary is required");
    }

    /**
     * Aggregates one run's comparison inputs into the report. Every amount
     * sum uses exact {@code long} addition on minor units — an overflow
     * fails loudly (never a silent wrap, never a float).
     */
    public static SettlementReport from(String id, ReconRun run, List<ProviderStatementLine> providerLines,
                                        List<InternalLedgerLine> internalLines, Instant now) {
        Objects.requireNonNull(run, "run is required");
        Map<String, Totals> byCurrency = new LinkedHashMap<>();
        java.util.Set<String> internalRefs = new java.util.HashSet<>();
        for (InternalLedgerLine line : internalLines) {
            if (line.isMatchable()) {
                internalRefs.add(line.providerRef());
            }
            Totals totals = byCurrency.computeIfAbsent(line.amount().currency(), Totals::new);
            totals.internalLines++;
            totals.internalVolume = addExact(totals.internalVolume, line.amount().amountMinor());
            totals.internalFees = addExact(totals.internalFees, line.fee().amountMinor());
        }
        for (ProviderStatementLine line : providerLines) {
            Totals totals = byCurrency.computeIfAbsent(line.amount().currency(), Totals::new);
            totals.providerLines++;
            totals.providerVolume = addExact(totals.providerVolume, line.amount().amountMinor());
            totals.providerFees = addExact(totals.providerFees, line.fee().amountMinor());
            if (internalRefs.contains(line.ref())) {
                totals.matchedPairs++;
            }
        }

        List<CurrencyLine> currencyLines = new ArrayList<>(byCurrency.size());
        for (Totals totals : byCurrency.values()) {
            currencyLines.add(new CurrencyLine(totals.currency, totals.providerLines,
                    totals.providerVolume, totals.providerFees, totals.internalLines,
                    totals.internalVolume, totals.internalFees, totals.matchedPairs));
        }
        return new SettlementReport(id, run.id(), run.provider(), run.window(), now, currencyLines,
                BreakSummary.empty());
    }

    /** Attaches the break counts of the run (call after breaks recorded). */
    public SettlementReport withBreaks(BreakSummary summary) {
        return new SettlementReport(id, runId, provider, window, generatedAt, currencyLines, summary);
    }

    /** Rehydrates a report from storage (all fields, no aggregation). */
    public static SettlementReport rehydrate(String id, String runId, String provider,
                                              ReconWindow window, Instant generatedAt,
                                              List<CurrencyLine> currencyLines,
                                              BreakSummary breakSummary) {
        return new SettlementReport(id, runId, provider, window, generatedAt, currencyLines,
                breakSummary);
    }

    private static long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            throw new MoneyOverflowException("settlement report total overflow", overflow);
        }
    }

    public String id() {
        return id;
    }

    public String runId() {
        return runId;
    }

    public String provider() {
        return provider;
    }

    public ReconWindow window() {
        return window;
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public List<CurrencyLine> currencyLines() {
        return currencyLines;
    }

    public BreakSummary breakSummary() {
        return breakSummary;
    }

    private static final class Totals {

        private final String currency;
        private int providerLines;
        private long providerVolume;
        private long providerFees;
        private int internalLines;
        private long internalVolume;
        private long internalFees;
        private int matchedPairs;

        private Totals(String currency) {
            this.currency = currency;
        }
    }

    /**
     * One currency's both-side totals.
     *
     * @param providerLines    provider statement lines in this currency
     * @param providerVolume   Σ provider principal amounts (minor units)
     * @param providerFees     Σ provider fees (minor units)
     * @param internalLines    internal ledger lines in this currency
     * @param internalVolume   Σ internal principal amounts (minor units)
     * @param internalFees     Σ internal fees (minor units)
     * @param matchedPairs     references that met on both sides
     */
    public record CurrencyLine(String currency, int providerLines, long providerVolume,
                               long providerFees, int internalLines, long internalVolume,
                               long internalFees, int matchedPairs) {
    }

    /** Break counts by taxonomy entry. */
    public record BreakSummary(int missingOnProvider, int missingInternal, int amountMismatch,
                               int statusMismatch, int feeMismatch, int currencyMismatch) {

        static BreakSummary empty() {
            return new BreakSummary(0, 0, 0, 0, 0, 0);
        }

        public static BreakSummary fromBreaks(List<DetectedBreak> breaks) {
            int missingOnProvider = 0;
            int missingInternal = 0;
            int amountMismatch = 0;
            int statusMismatch = 0;
            int feeMismatch = 0;
            int currencyMismatch = 0;
            for (DetectedBreak detected : breaks) {
                switch (detected.breakType()) {
                    case MISSING_ON_PROVIDER -> missingOnProvider++;
                    case MISSING_INTERNAL -> missingInternal++;
                    case AMOUNT_MISMATCH -> amountMismatch++;
                    case STATUS_MISMATCH -> statusMismatch++;
                    case FEE_MISMATCH -> feeMismatch++;
                    case CURRENCY_MISMATCH -> currencyMismatch++;
                }
            }
            return new BreakSummary(missingOnProvider, missingInternal, amountMismatch,
                    statusMismatch, feeMismatch, currencyMismatch);
        }

        public int total() {
            return missingOnProvider + missingInternal + amountMismatch + statusMismatch
                    + feeMismatch + currencyMismatch;
        }
    }
}
