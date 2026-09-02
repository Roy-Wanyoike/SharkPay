package com.sharkpay.reconciliation.fakes;

import com.sharkpay.reconciliation.ports.LedgerPort;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-tree fake of the ledger posting port (src/test, ADR 003 §3) — the
 * executable specification of the real Go ledger's posting contract
 * (services/ledger/internal/api/server.go). Enforces every structural
 * invariant the real ledger enforces:
 *
 * <ul>
 *   <li>per-currency balance: for every journal entry Σdebit = Σcredit
 *       (DATA-MODEL §4.2);</li>
 *   <li>idempotency on (source, transaction_key): a duplicate post returns
 *       the ORIGINAL entry id with {@code replay = true} and posts nothing
 *       new (DATA-MODEL §4.3) — the exactly-once guarantee compensation
 *       retries rely on;</li>
 *   <li>reversal pairing: a REVERSAL must reference a previously committed
 *       entry (DATA-MODEL §4.4);</li>
 *   <li>{@link #rejectNext(String, String)} simulates a business rejection
 *       (e.g. insufficient_funds under row locks) so 4-eyes tests can
 *       prove nothing posts and the entry stays PROPOSED.</li>
 * </ul>
 *
 * <p>Counters expose attempts vs committed effects — the oracle for
 * "executes exactly once" assertions.</p>
 */
public final class FakeLedgerPort implements LedgerPort {

    private final Map<String, UUID> entryIdsByKey = new LinkedHashMap<>();
    private final List<LedgerPosting> committed = new ArrayList<>();
    private final List<Rejected> rejections = new ArrayList<>();
    private int attempts;
    private Rejected nextRejection;

    @Override
    public synchronized PostingResult post(LedgerPosting posting) {
        attempts++;
        if (nextRejection != null) {
            Rejected rejection = nextRejection;
            nextRejection = null;
            rejections.add(rejection);
            return new PostingResult.Rejected(rejection.code(), rejection.reason());
        }
        String imbalance = imbalancePerCurrency(posting);
        if (imbalance != null) {
            return new PostingResult.Rejected("unbalanced_entry", imbalance);
        }
        String key = posting.source().wireName() + "|" + posting.transactionKey();
        UUID existing = entryIdsByKey.get(key);
        if (existing != null) {
            return new PostingResult.Committed(existing, true);
        }
        if (posting.entryType() == EntryType.REVERSAL
                && !entryIdsByKey.containsValue(posting.reversesEntryId())) {
            return new PostingResult.Rejected("reversal_mismatch",
                    "no committed entry " + posting.reversesEntryId() + " to reverse");
        }
        UUID entryId = UUID.randomUUID();
        entryIdsByKey.put(key, entryId);
        committed.add(posting);
        return new PostingResult.Committed(entryId, false);
    }

    /** Null when balanced; otherwise the imbalance description (per currency). */
    private static String imbalancePerCurrency(LedgerPosting posting) {
        Map<String, BigInteger> balance = new LinkedHashMap<>();
        for (Leg leg : posting.legs()) {
            BigInteger signed = BigInteger.valueOf(leg.amount().amountMinor())
                    .multiply(BigInteger.valueOf(leg.direction() == Direction.DEBIT ? 1 : -1));
            balance.merge(leg.amount().currency(), signed, BigInteger::add);
        }
        for (Map.Entry<String, BigInteger> entry : balance.entrySet()) {
            if (entry.getValue().signum() != 0) {
                return "entry is off by " + entry.getValue() + " " + entry.getKey()
                        + " minor units (Σdebit ≠ Σcredit)";
            }
        }
        return null;
    }

    /** The next post is rejected with the given business code. */
    public FakeLedgerPort rejectNext(String code, String reason) {
        nextRejection = new Rejected(code, reason);
        return this;
    }

    /** Post attempts (including replays and rejections). */
    public int attempts() {
        return attempts;
    }

    /** Posts that actually committed a new journal entry (no replays). */
    public List<LedgerPosting> committedPostings() {
        return List.copyOf(committed);
    }

    public int committedCount() {
        return committed.size();
    }

    public List<Rejected> rejections() {
        return List.copyOf(rejections);
    }

    /** True when the compensation key already posted (idempotency oracle). */
    public boolean hasCommitted(String transactionKey) {
        return entryIdsByKey.keySet().stream().anyMatch(key -> key.endsWith("|" + transactionKey));
    }

    /** The journal entry id committed under a transaction key. */
    public UUID entryIdOf(String transactionKey) {
        return entryIdsByKey.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("|" + transactionKey))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public record Rejected(String code, String reason) {
    }
}
