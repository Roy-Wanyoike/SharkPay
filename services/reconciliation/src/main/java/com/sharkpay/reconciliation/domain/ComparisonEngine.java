package com.sharkpay.reconciliation.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The pure comparison engine (PRD D10 / FR-1001). Matches provider
 * statement lines against internal ledger lines <b>by reference</b> and
 * classifies every discrepancy into the {@link BreakType} taxonomy.
 *
 * <p><b>Matching semantics.</b> A provider line matches the internal line
 * with the same {@code providerRef} (first one wins; the
 * {@code LedgerStatementPort} contract requires internal lines to be unique
 * by provider ref — a duplicate is rejected loudly here, never
 * misclassified). An internal line without a provider ref can never match:
 * it is an internal movement the provider does not know, i.e.
 * MISSING_ON_PROVIDER. A provider line without an internal counterpart is
 * MISSING_INTERNAL. Every line on both sides is classified exactly
 * once.</p>
 *
 * <p><b>Pair comparison.</b> For a matched pair, each dimension is judged
 * independently, so one pair can yield up to three breaks (status, amount,
 * fee — the dimensions of the discrepancy are exactly the dimensions of the
 * investigation):</p>
 * <ol>
 *   <li><b>Status</b> — the provider's raw status is mapped through
 *       {@link StatusMappings}; an unmappable status, or a mapped status
 *       differing from the (likewise mapped) internal status, is a
 *       STATUS_MISMATCH. The engine never guesses a status (provider
 *       AMBIGUITY CONTRACT).</li>
 *   <li><b>Currency</b> — differing currencies (principal or fee) are a
 *       CURRENCY_MISMATCH of their own; minor units of different currencies
 *       are incomparable, so numeric comparison is skipped for that
 *       pair.</li>
 *   <li><b>Amount</b> — same currency, exact {@code long} minor-unit
 *       inequality → AMOUNT_MISMATCH. Never floats.</li>
 *   <li><b>Fee</b> — same currency, exact minor-unit inequality →
 *       FEE_MISMATCH.</li>
 * </ol>
 *
 * <p>Duplicate refs within one provider statement are compared
 * independently against the same internal line (the statement-reading side
 * must investigate duplicates; the engine classifies only what the
 * taxonomy can express).</p>
 */
public final class ComparisonEngine {

    private ComparisonEngine() {
    }

    /**
     * Compares the two sides of one window and returns the classification.
     *
     * @throws IllegalArgumentException when internal lines repeat a provider
     *                                  ref (port contract violation — loud,
     *                                  never misclassified)
     */
    public static Result compare(List<ProviderStatementLine> providerLines,
                                 List<InternalLedgerLine> internalLines) {
        Objects.requireNonNull(providerLines, "providerLines is required");
        Objects.requireNonNull(internalLines, "internalLines is required");

        Map<String, InternalLedgerLine> internalByRef = new LinkedHashMap<>();
        for (InternalLedgerLine line : internalLines) {
            if (line.isMatchable()) {
                InternalLedgerLine prior = internalByRef.putIfAbsent(line.providerRef(), line);
                if (prior != null) {
                    throw new IllegalArgumentException(
                            "internal lines must be unique by provider ref: " + line.providerRef()
                                    + " appears more than once (lines " + prior.internalRef()
                                    + " and " + line.internalRef() + ")");
                }
            }
        }

        java.util.Set<String> providerRefs = new java.util.HashSet<>();
        for (ProviderStatementLine providerLine : providerLines) {
            providerRefs.add(providerLine.ref());
        }

        List<DetectedBreak> breaks = new ArrayList<>();
        int matchedPairs = 0;
        for (ProviderStatementLine providerLine : providerLines) {
            InternalLedgerLine internal = internalByRef.get(providerLine.ref());
            if (internal == null) {
                breaks.add(new DetectedBreak(BreakType.MISSING_INTERNAL, providerLine.ref(), null,
                        providerLine.amount(), null, providerLine.fee(), null,
                        providerLine.status(), null));
                continue;
            }
            matchedPairs++;
            breaks.addAll(comparePair(providerLine, internal));
        }

        for (InternalLedgerLine line : internalLines) {
            boolean matched = line.isMatchable() && providerRefs.contains(line.providerRef());
            if (!matched) {
                breaks.add(new DetectedBreak(BreakType.MISSING_ON_PROVIDER, line.providerRef(),
                        line.internalRef(), null, line.amount(), null, line.fee(), null,
                        line.status()));
            }
        }

        return new Result(matchedPairs, List.copyOf(breaks));
    }

    /** Breaks for one matched pair (status / currency / amount / fee). */
    private static List<DetectedBreak> comparePair(ProviderStatementLine providerLine,
                                                   InternalLedgerLine internalLine) {
        List<DetectedBreak> breaks = new ArrayList<>(3);

        // 1. status — map, never guess
        Optional<ReconStatus> providerStatus = StatusMappings.canonical(providerLine.status());
        Optional<ReconStatus> internalStatus = StatusMappings.canonical(internalLine.status());
        if (providerStatus.isEmpty() || internalStatus.isEmpty()
                || providerStatus.get() != internalStatus.get()) {
            breaks.add(new DetectedBreak(BreakType.STATUS_MISMATCH, providerLine.ref(),
                    internalLine.internalRef(), providerLine.amount(), internalLine.amount(),
                    providerLine.fee(), internalLine.fee(), providerLine.status(),
                    internalLine.status()));
        }

        // 2. currency — its own break; numbers become incomparable
        boolean currencyMismatch = !providerLine.amount().currency()
                .equals(internalLine.amount().currency())
                || !providerLine.fee().currency().equals(internalLine.fee().currency());
        if (currencyMismatch) {
            breaks.add(new DetectedBreak(BreakType.CURRENCY_MISMATCH, providerLine.ref(),
                    internalLine.internalRef(), providerLine.amount(), internalLine.amount(),
                    providerLine.fee(), internalLine.fee(), providerLine.status(),
                    internalLine.status()));
            return breaks;
        }

        // 3. amount — exact minor units, same currency, never floats
        if (providerLine.amount().amountMinor() != internalLine.amount().amountMinor()) {
            breaks.add(new DetectedBreak(BreakType.AMOUNT_MISMATCH, providerLine.ref(),
                    internalLine.internalRef(), providerLine.amount(), internalLine.amount(),
                    providerLine.fee(), internalLine.fee(), providerLine.status(),
                    internalLine.status()));
        }

        // 4. fee — exact minor units, same currency
        if (providerLine.fee().amountMinor() != internalLine.fee().amountMinor()) {
            breaks.add(new DetectedBreak(BreakType.FEE_MISMATCH, providerLine.ref(),
                    internalLine.internalRef(), providerLine.amount(), internalLine.amount(),
                    providerLine.fee(), internalLine.fee(), providerLine.status(),
                    internalLine.status()));
        }

        return breaks;
    }

    /**
     * @param matchedPairs number of provider lines that found an internal
     *                     counterpart (a matched pair may still produce
     *                     breaks)
     * @param breaks       every classified discrepancy, provider lines in
     *                     statement order, then unmatched internal lines
     */
    public record Result(int matchedPairs, List<DetectedBreak> breaks) {

        public Result {
            Objects.requireNonNull(breaks, "breaks is required");
        }

        public int breakCount() {
            return breaks.size();
        }
    }
}
