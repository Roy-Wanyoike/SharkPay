package com.sharkpay.reconciliation.ports;

import com.sharkpay.money.Money;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Consumer-driven port to the Go ledger service — the only writer of
 * journal entries — used by the reconciliation service exclusively for
 * 4-eyes-controlled compensation postings (RB-7 steps 3–4). Mirrors the
 * ledger's internal HTTP API semantics 1:1
 * (services/ledger/internal/api/server.go), the same contract
 * {@code services/payouts/ports/LedgerPort.java} is built on:
 *
 * <ul>
 *   <li>{@code POST /internal/transactions} → {@link #post(LedgerPosting)}:
 *       a balanced journal entry (≥ 2 legs, per-currency
 *       Σdebit = Σcredit) idempotent on {@code (source, transactionKey)};
 *       a duplicate returns the original entry id with {@code replay =
 *       true} and posts nothing new — this is what makes a compensation
 *       retry safe (the compensation key is the RB-7
 *       {@code ops:adj:<break_id>} key);</li>
 *   <li>an {@code adjustment} entry (RB-7: legs involving
 *       {@code suspense:recon:KES} / {@code honeycoin:settlement:KES}) or a
 *       {@code reversal} entry referencing the entry it compensates
 *       ({@code reversesEntryId} — never a mutation of history,
 *       DATA-MODEL §4.4/4.5).</li>
 * </ul>
 *
 * <p>Outcomes are values, not exceptions: a business rejection (per-entry
 * balance, non-negativity under row locks, reversal pairing) is
 * {@link PostingResult#rejected} — the compensation stays PROPOSED and the
 * caller surfaces 422; transport failures are thrown by adapters. The
 * production REST adapter lands at integration (fail-fast placeholder in
 * {@code com.sharkpay.reconciliation.config} until then, ADR 003 §3); local
 * tests run on the in-tree fake, which enforces every structural invariant
 * the real ledger enforces (≥ 2 legs, per-currency balance, key format,
 * reversal pairing, idempotency on the key).</p>
 */
public interface LedgerPort {

    /** Posts one atomic journal entry (idempotent on the transaction key). */
    PostingResult post(LedgerPosting posting);

    /** Journal entry type (ledger entry_type wire names). */
    enum EntryType {
        CAPTURE("capture"), HOLD("hold"), RELEASE("release"), REVERSAL("reversal"),
        FEE("fee"), FX("fx"), ADJUSTMENT("adjustment");

        private final String wireName;

        EntryType(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Posting source (ledger source wire names; keys must start with it). */
    enum Source {
        PAYMENTS("payments"), PAYOUTS("payouts"), TRANSFERS("transfers"), FX("fx"),
        FEES("fees"), OPS("ops");

        private final String wireName;

        Source(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Debit or credit side of one journal leg. */
    enum Direction {
        DEBIT("debit"), CREDIT("credit");

        private final String wireName;

        Direction(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public Direction opposite() {
            return this == DEBIT ? CREDIT : DEBIT;
        }
    }

    /**
     * One journal leg: the account it touches (a wallet ledger-account UUID
     * string for principal wallets, or an internal account ref like
     * {@code suspense:recon:KES}), the side, and the positive amount.
     */
    record Leg(String accountRef, Direction direction, Money amount) {

        public Leg {
            Objects.requireNonNull(accountRef, "accountRef is required");
            if (accountRef.isBlank()) {
                throw new IllegalArgumentException("accountRef must not be blank");
            }
            Objects.requireNonNull(direction, "direction is required");
            Objects.requireNonNull(amount, "amount is required");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("leg amount must be positive: " + amount);
            }
        }

        /** The mirrored leg against the same account (reversal derivation). */
        Leg inverted() {
            return new Leg(accountRef, direction.opposite(), amount);
        }
    }

    /**
     * A journal entry request — the unit of atomic persistence. Mirrors the
     * ledger's domain {@code Transaction}: key {@code source:ref[:subtype]}
     * (globally unique, must start with the entry's source), a UUID
     * source_ref, the entry type, an optional reference to the entry this
     * one compensates (required for REVERSAL), a bounded reason, and the
     * ordered legs (≥ 2, balanced per currency).
     */
    record LedgerPosting(String transactionKey, Source source, UUID sourceRef, EntryType entryType,
                         UUID reversesEntryId, String reason, List<Leg> legs) {

        public LedgerPosting {
            Objects.requireNonNull(transactionKey, "transactionKey is required");
            Objects.requireNonNull(source, "source is required");
            Objects.requireNonNull(sourceRef, "sourceRef is required");
            Objects.requireNonNull(entryType, "entryType is required");
            Objects.requireNonNull(legs, "legs is required");
            if (legs.size() < 2) {
                throw new IllegalArgumentException(
                        "a journal entry needs at least 2 postings, got " + legs.size());
            }
            if (!transactionKey.startsWith(source.wireName() + ":")) {
                throw new IllegalArgumentException("transaction key " + transactionKey
                        + " must start with source " + source.wireName());
            }
            if (transactionKey.length() < 5 || transactionKey.length() > 128) {
                throw new IllegalArgumentException(
                        "transaction key must be 5-128 characters: " + transactionKey);
            }
            if (reason != null && reason.length() > 500) {
                throw new IllegalArgumentException("reason must be at most 500 characters");
            }
            if (entryType == EntryType.REVERSAL && reversesEntryId == null) {
                throw new IllegalArgumentException(
                        "reversal entries must reference the entry they compensate");
            }
            if (entryType != EntryType.REVERSAL && reversesEntryId != null) {
                throw new IllegalArgumentException(
                        "reverses_entry_id is only allowed on reversal entries");
            }
            legs = List.copyOf(legs);
        }

        /** Builds a plain (non-reversal) posting. */
        public static LedgerPosting of(String transactionKey, Source source, UUID sourceRef,
                                       EntryType entryType, String reason, List<Leg> legs) {
            return new LedgerPosting(transactionKey, source, sourceRef, entryType, null, reason,
                    legs);
        }

        /** Builds a compensation posting referencing the reversed entry. */
        public static LedgerPosting reversalOf(String transactionKey, Source source,
                                               UUID sourceRef, UUID reversesEntryId, String reason,
                                               List<Leg> legs) {
            return new LedgerPosting(transactionKey, source, sourceRef, EntryType.REVERSAL,
                    reversesEntryId, reason, legs);
        }
    }

    /**
     * The outcome of a post: committed (with the journal entry id —
     * {@code replay=true} on an idempotent duplicate that returned the
     * original entry), or rejected by a ledger business rule (money
     * authority: e.g. the per-entry balance invariant under row locks).
     */
    sealed interface PostingResult {

        record Committed(UUID entryId, boolean replay) implements PostingResult {
        }

        record Rejected(String code, String reason) implements PostingResult {
        }
    }
}
