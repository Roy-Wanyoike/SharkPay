package com.sharkpay.wallet.ledger;

import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code ledger.posting.committed.v1} CloudEvent envelope this service
 * consumes (contracts/events/ledger.posting.v1.json — read-only contract;
 * record component names are the wire's snake_case field names so the
 * transport adapter can bind JSON directly).
 *
 * <p>{@link #validate()} enforces the structural contract the ledger
 * guarantees upstream: envelope constants, at least two legs, one-sided
 * non-negative legs (debit XOR credit nonzero) and monotonic posting ids.
 * A violation means a malformed/forged event; the consumer rejects it for
 * dead-lettering rather than corrupting the projection.
 */
public record LedgerPostingEvent(String id, String type, String specversion, String source,
                                 String subject, Instant occurred_at, LedgerData data) {

    public static final String TYPE = "ledger.posting.committed.v1";
    public static final String SOURCE = "sharkpay/ledger";
    public static final String SPECVERSION = "1.0";

    public LedgerPostingEvent {
        Objects.requireNonNull(id, "event id is required");
        Objects.requireNonNull(type, "event type is required");
        Objects.requireNonNull(specversion, "specversion is required");
        Objects.requireNonNull(source, "event source is required");
        Objects.requireNonNull(data, "event data is required");
        if (occurred_at == null) {
            throw new IllegalArgumentException("occurred_at is required");
        }
    }

    /** Validates envelope + leg structure (throws on contract violations). */
    public void validate() {
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("event type must be " + TYPE + ": " + type);
        }
        if (!SPECVERSION.equals(specversion)) {
            throw new IllegalArgumentException("specversion must be " + SPECVERSION + ": " + specversion);
        }
        if (!SOURCE.equals(source)) {
            throw new IllegalArgumentException("event source must be " + SOURCE + ": " + source);
        }
        data.validate();
    }

    /**
     * The committed journal entry: id, transaction key, business source,
     * entry type and every posting leg.
     */
    public record LedgerData(UUID entry_id, String transaction_key, Source source,
                             UUID source_ref, String entry_type, UUID reverses_entry_id,
                             String reason, UUID operator_id, List<Posting> postings) {

        /** Journal entry types (docs/DATA-MODEL.md §3.1, wallets.yaml EntryType). */
        public static final List<String> ENTRY_TYPES = List.of(
                "capture", "hold", "release", "reversal", "fee", "fx", "adjustment");

        public LedgerData {
            Objects.requireNonNull(entry_id, "entry_id is required");
            Objects.requireNonNull(transaction_key, "transaction_key is required");
            Objects.requireNonNull(source, "source is required");
            Objects.requireNonNull(entry_type, "entry_type is required");
            postings = List.copyOf(postings == null ? List.of() : postings);
        }

        void validate() {
            if (!ENTRY_TYPES.contains(entry_type)) {
                throw new IllegalArgumentException("unknown entry_type: " + entry_type);
            }
            if (postings.size() < 2) {
                throw new IllegalArgumentException(
                        "a journal entry has at least 2 legs, got " + postings.size());
            }
            long previous = 0L;
            for (Posting posting : postings) {
                posting.validate();
                if (posting.posting_id <= previous) {
                    throw new IllegalArgumentException(
                            "posting ids must be strictly increasing within an entry: "
                                    + posting.posting_id + " after " + previous);
                }
                previous = posting.posting_id;
            }
        }
    }

    /**
     * One leg of the entry, exactly as persisted by the ledger:
     * {@code debit XOR credit} nonzero, minor units.
     */
    public record Posting(long posting_id, UUID account_id, String account_code,
                          String currency, long debit, long credit) {

        public Posting {
            Objects.requireNonNull(account_id, "account_id is required");
        }

        void validate() {
            if (posting_id < 1) {
                throw new IllegalArgumentException("posting_id must be >= 1: " + posting_id);
            }
            if (debit < 0L || credit < 0L) {
                throw new IllegalArgumentException(
                        "leg amounts must be non-negative: debit=" + debit + " credit=" + credit);
            }
            if ((debit > 0L) == (credit > 0L)) {
                throw new IllegalArgumentException(
                        "legs are one-sided (debit XOR credit nonzero): debit=" + debit
                                + " credit=" + credit);
            }
        }

        /** The wire direction of this leg. */
        public Direction direction() {
            return debit > 0L ? Direction.DEBIT : Direction.CREDIT;
        }

        /** The minor-unit amount of the (single) non-zero side. */
        public long amountMinor() {
            return debit > 0L ? debit : credit;
        }
    }
}
