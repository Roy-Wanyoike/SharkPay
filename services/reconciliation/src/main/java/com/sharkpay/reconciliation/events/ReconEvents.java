package com.sharkpay.reconciliation.events;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.ports.Randomness;

import java.time.Duration;
import java.time.Instant;

/**
 * Event factories for the reconciliation event types (envelope per
 * contracts/events/events.md; payloads validated against
 * contracts/events/recon.v1.json in ReconEventsTest — field names are
 * snake_case exactly as the schema specifies, optional fields omitted when
 * null):
 *
 * <ul>
 *   <li>{@code recon.run.completed.v1} — the comparison executed; carries
 *       the window and the line/break counts (the daily recon report
 *       feed);</li>
 *   <li>{@code recon.break.detected.v1} — the event RB-7's symptoms name;
 *       one per recorded break, both sides' facts included;</li>
 *   <li>{@code recon.break.escalated.v1} — the RB-7 ops alert: published
 *       exactly once per aging-bucket transition (AGING = page,
 *       STALE = S2-minimum escalation), with the bucket and age;</li>
 *   <li>{@code recon.compensation.executed.v1} — a 4-eyes compensation
 *       posted through the ledger, with both principals and the journal
 *       entry id.</li>
 * </ul>
 */
public final class ReconEvents {

    /** contracts/events/recon.v1.json. */
    public static final String RUN_COMPLETED = "recon.run.completed.v1";
    /** contracts/events/recon.v1.json (named by RB-7's symptoms). */
    public static final String BREAK_DETECTED = "recon.break.detected.v1";
    /** contracts/events/recon.v1.json (RB-7 ops alert). */
    public static final String BREAK_ESCALATED = "recon.break.escalated.v1";
    /** contracts/events/recon.v1.json. */
    public static final String COMPENSATION_EXECUTED = "recon.compensation.executed.v1";

    private final Randomness randomness;

    public ReconEvents(Randomness randomness) {
        this.randomness = randomness;
    }

    /** Builds the {@code recon.run.completed.v1} event. */
    public CloudEvent runCompleted(ReconRun run, Instant occurredAt) {
        return new CloudEvent(randomness.uuidV7().toString(), RUN_COMPLETED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, run.id(), occurredAt,
                new RunCompletedData(run.id(), run.provider(), run.window().from(),
                        run.window().to(), run.providerLines(), run.internalLines(),
                        run.matchedPairs(), run.breakCount()));
    }

    /** Builds the {@code recon.break.detected.v1} event. */
    public CloudEvent breakDetected(ReconBreak break_, Instant occurredAt) {
        return new CloudEvent(randomness.uuidV7().toString(), BREAK_DETECTED, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, break_.id(), occurredAt,
                new BreakDetectedData(break_.id(), break_.runId(), break_.provider(),
                        break_.breakType().wireName(), break_.providerRef(), break_.internalRef(),
                        MoneyField.of(break_.providerAmount()), MoneyField.of(break_.internalAmount()),
                        MoneyField.of(break_.providerFee()), MoneyField.of(break_.internalFee()),
                        break_.providerStatus(), break_.internalStatus(),
                        break_.state().wireName(), break_.detectedAt()));
    }

    /**
     * Builds the {@code recon.break.escalated.v1} event (RB-7 ops alert;
     * one per aging-bucket transition).
     */
    public CloudEvent breakEscalated(ReconBreak break_, AgingBucket bucket, Instant occurredAt) {
        long ageHours = Duration.between(break_.detectedAt(), occurredAt).toHours();
        return new CloudEvent(randomness.uuidV7().toString(), BREAK_ESCALATED,
                CloudEvent.SPECVERSION, CloudEvent.SOURCE, break_.id(), occurredAt,
                new BreakEscalatedData(break_.id(), break_.runId(), break_.provider(),
                        break_.breakType().wireName(), bucket.wireName(), Math.max(0, ageHours),
                        break_.state().wireName()));
    }

    /** Builds the {@code recon.compensation.executed.v1} event. */
    public CloudEvent compensationExecuted(CompensationEntry entry, Instant occurredAt) {
        return new CloudEvent(randomness.uuidV7().toString(), COMPENSATION_EXECUTED,
                CloudEvent.SPECVERSION, CloudEvent.SOURCE, entry.id(), occurredAt,
                new CompensationExecutedData(entry.id(), entry.breakId(), entry.provider(),
                        entry.requester(), entry.approver(), entry.ledgerEntryId().toString(),
                        entry.compensationKey()));
    }

    /** Payload of {@code recon.run.completed.v1}. */
    public record RunCompletedData(String run_id, String provider,
                                   java.time.Instant window_from, java.time.Instant window_to,
                                   int provider_lines, int internal_lines, int matched_lines,
                                   int break_count) {
    }

    /** Payload of {@code recon.break.detected.v1} (absent side → omitted). */
    public record BreakDetectedData(String break_id, String run_id, String provider, String break_type,
                                    String provider_ref, String internal_ref, MoneyField provider_amount,
                                    MoneyField internal_amount, MoneyField provider_fee,
                                    MoneyField internal_fee, String provider_status,
                                    String internal_status, String state, java.time.Instant detected_at) {
    }

    /** Payload of {@code recon.break.escalated.v1} (RB-7 ops alert). */
    public record BreakEscalatedData(String break_id, String run_id, String provider, String break_type,
                                     String bucket, long age_hours, String state) {
    }

    /** Payload of {@code recon.compensation.executed.v1}. */
    public record CompensationExecutedData(String compensation_id, String break_id, String provider,
                                           String requester, String approver, String ledger_entry_id,
                                           String compensation_key) {
    }

    /**
     * Integer-only money field (docs/API-CONTRACTS.md §1.6); null-safe so a
     * break's absent side is simply omitted (NON_NULL inclusion).
     */
    public record MoneyField(long amount_minor, String currency, int exponent) {

        public static MoneyField of(com.sharkpay.money.Money money) {
            return money == null ? null
                    : new MoneyField(money.amountMinor(), money.currency(), money.exponent());
        }
    }
}
