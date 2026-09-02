package com.sharkpay.payouts.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.ports.LedgerPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-tree fake of the Go ledger's internal posting API (ADR 003 §3),
 * enforcing EVERY structural invariant the real service enforces
 * (services/ledger/internal/api/server.go):
 *
 * <ul>
 *   <li><b>≥ 2 legs</b> — the {@code LedgerPosting} record itself rejects
 *       fewer, and the fake re-asserts it loudly so a regression can never
 *       hide behind a silent record change;</li>
 *   <li><b>per-currency balance</b> — debits must equal credits for every
 *       currency of the entry; an unbalanced entry throws (structural
 *       violation, never a business outcome);</li>
 *   <li><b>idempotent key replay</b> — a second post of the same
 *       {@code transactionKey} returns the ORIGINAL entry id with
 *       {@code replay = true} and posts nothing new;</li>
 *   <li><b>reversal pairing</b> — {@code reverse()} must reference an
 *       existing entry (unknown entry id is a structural violation), a
 *       REVERSAL posting must reference an existing compensated entry, and
 *       a non-reversal posting must not carry {@code reverses_entry_id};</li>
 *   <li><b>account non-negativity</b> — no account (wallet or internal)
 *       may go negative; a wallet-account overdraft is returned as
 *       {@code Rejected("insufficient_funds", …)} exactly like the ledger's
 *       row-locked business check, an internal-account overdraft throws
 *       (structural).</li>
 * </ul>
 *
 * <p>Effect counters separate <b>attempts</b> (invocations) from
 * <b>effects</b> (journal rows) so money-safety tests can assert "single
 * atomic posting / compensation exactly once". Wallet accounts are seeded
 * with their opening balance by the test environment.</p>
 */
public final class FakeLedgerPort implements LedgerPort {

    /** One committed journal entry, as recorded by the fake. */
    public record RecordedEntry(UUID entryId, String transactionKey, UUID sourceRef,
                                EntryType entryType, UUID reversesEntryId, String reason,
                                List<Leg> legs) {
    }

    private final Map<String, RecordedEntry> entriesByKey = new ConcurrentHashMap<>();
    private final Map<UUID, RecordedEntry> entriesById = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();
    /** accountRef -> currency -> minor units (credits − debits). */
    private final Map<String, Map<String, Long>> balances = new ConcurrentHashMap<>();
    private final List<RecordedEntry> journal = new ArrayList<>();
    private final Map<String, Rejection> rejectionsByKey = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> failuresByKey = new ConcurrentHashMap<>();
    private final List<String> rejectionPrefixes = new ArrayList<>();
    private final List<String> failurePrefixes = new ArrayList<>();
    private final AtomicInteger uuidSeq = new AtomicInteger();

    // ── scripting knobs ────────────────────────────────────────────────────

    /** Seeds an account's opening balance (test wallet snapshot parity). */
    public synchronized void seed(String accountRef, Money amount) {
        balances.computeIfAbsent(accountRef, ignored -> new ConcurrentHashMap<>())
                .merge(amount.currency(), amount.amountMinor(), Long::sum);
    }

    /** Makes the next posts of {@code transactionKey} a business rejection. */
    public void reject(String transactionKey, String code, String reason) {
        rejectionsByKey.put(transactionKey, new Rejection(code, reason));
    }

    /** Makes posts of any key starting with {@code prefix} a business rejection. */
    public synchronized void rejectPrefix(String prefix) {
        rejectionPrefixes.add(prefix);
    }

    /** Makes posts of {@code transactionKey} throw (transport failure). */
    public void failOn(String transactionKey, RuntimeException failure) {
        failuresByKey.put(transactionKey, failure);
    }

    /** Makes posts of any key starting with {@code prefix} throw (transport failure). */
    public synchronized void failPrefix(String prefix, RuntimeException failure) {
        failurePrefixes.add(prefix);
        failuresByKey.put("prefix:" + prefix, failure);
    }

    // ── port surface ───────────────────────────────────────────────────────

    @Override
    public synchronized PostingResult post(LedgerPosting posting) {
        attempt(posting.transactionKey());
        RecordedEntry existing = entriesByKey.get(posting.transactionKey());
        if (existing != null) {
            // idempotent replay: the original entry id, no new journal row
            return new PostingResult.Committed(existing.entryId(), true);
        }
        RuntimeException failure = failuresByKey.get(posting.transactionKey());
        if (failure == null) {
            synchronized (this) {
                for (String prefix : failurePrefixes) {
                    if (posting.transactionKey().startsWith(prefix)) {
                        failure = failuresByKey.get("prefix:" + prefix);
                        break;
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        Rejection rejection = rejectionsByKey.get(posting.transactionKey());
        if (rejection == null) {
            synchronized (this) {
                for (String prefix : rejectionPrefixes) {
                    if (posting.transactionKey().startsWith(prefix)) {
                        rejection = new Rejection("insufficient_funds",
                                "wallet balance check failed (scripted prefix " + prefix + ")");
                        break;
                    }
                }
            }
        }
        if (rejection != null) {
            return new PostingResult.Rejected(rejection.code(), rejection.reason());
        }
        validateStructure(posting);
        String overdraft = applyLegs(posting.legs(), true);
        if (overdraft != null) {
            // roll the entry back — a rejected posting lands NO leg
            applyLegs(inverted(posting.legs()), false);
            return new PostingResult.Rejected("insufficient_funds", overdraft);
        }
        RecordedEntry entry = record(posting);
        return new PostingResult.Committed(entry.entryId(), false);
    }

    @Override
    public synchronized PostingResult reverse(UUID entryId, String transactionKey, UUID sourceRef,
                                              String reason) {
        attempt(transactionKey);
        RecordedEntry existing = entriesByKey.get(transactionKey);
        if (existing != null) {
            return new PostingResult.Committed(existing.entryId(), true);
        }
        RuntimeException failure = failuresByKey.get(transactionKey);
        if (failure != null) {
            throw failure;
        }
        Rejection rejection = rejectionsByKey.get(transactionKey);
        if (rejection != null) {
            return new PostingResult.Rejected(rejection.code(), rejection.reason());
        }
        RecordedEntry target = entriesById.get(entryId);
        if (target == null) {
            throw new IllegalStateException("reversal pairing violation: entry " + entryId
                    + " does not exist in the journal (key " + transactionKey + ")");
        }
        List<Leg> inverse = inverted(target.legs());
        LedgerPosting reversal = LedgerPort.LedgerPosting.reversalOf(transactionKey,
                sourceOf(transactionKey), sourceRef, entryId, reason, inverse);
        validateStructure(reversal);
        String overdraft = applyLegs(inverse, true);
        if (overdraft != null) {
            applyLegs(inverted(inverse), false);
            throw new IllegalStateException("reversal would overdraft " + overdraft
                    + " (structural — a release reversal must be strictly inverse)");
        }
        RecordedEntry entry = record(reversal);
        return new PostingResult.Committed(entry.entryId(), false);
    }

    // ── introspection ──────────────────────────────────────────────────────

    /** The full journal, oldest first. */
    public List<RecordedEntry> journal() {
        synchronized (this) {
            return List.copyOf(journal);
        }
    }

    /** The entry recorded under a transaction key, when posted. */
    public Optional<RecordedEntry> entry(String transactionKey) {
        return Optional.ofNullable(entriesByKey.get(transactionKey));
    }

    /** Journal-row count for a key: 0 or 1 (idempotent keys). */
    public int effectCount(String transactionKey) {
        return entriesByKey.containsKey(transactionKey) ? 1 : 0;
    }

    /** Invocation count for a key, including idempotent replays. */
    public int attemptCount(String transactionKey) {
        return attemptsByKey.getOrDefault(transactionKey, new AtomicInteger()).get();
    }

    /** The legs of the entry posted under a key, in order. */
    public List<Leg> legsOf(String transactionKey) {
        RecordedEntry entry = entriesByKey.get(transactionKey);
        return entry == null ? List.of() : entry.legs();
    }

    /** Net balance (credits − debits) of one account in one currency. */
    public long balanceOf(String accountRef, String currency) {
        return balances.getOrDefault(accountRef, Map.of()).getOrDefault(currency, 0L);
    }

    /** Net balances of one account across currencies. */
    public Map<String, Long> balancesOf(String accountRef) {
        return Map.copyOf(balances.getOrDefault(accountRef, Map.of()));
    }

    /** The entry a REVERSAL posting compensates, when referenced. */
    public Optional<RecordedEntry> reversedTarget(String transactionKey) {
        RecordedEntry entry = entriesByKey.get(transactionKey);
        return entry == null ? Optional.empty()
                : Optional.ofNullable(entriesById.get(entry.reversesEntryId()));
    }

    /** Total committed journal rows. */
    public int totalEffects() {
        return entriesByKey.size();
    }

    // ── internals ──────────────────────────────────────────────────────────

    private record Rejection(String code, String reason) {
    }

    private void attempt(String transactionKey) {
        attemptsByKey.computeIfAbsent(transactionKey, ignored -> new AtomicInteger())
                .incrementAndGet();
    }

    private void validateStructure(LedgerPosting posting) {
        if (posting.legs().size() < 2) {
            throw new IllegalStateException("structural violation: entry "
                    + posting.transactionKey() + " has " + posting.legs().size() + " legs (< 2)");
        }
        Map<String, Long> perCurrency = new LinkedHashMap<>();
        for (Leg leg : posting.legs()) {
            perCurrency.merge(leg.amount().currency(),
                    leg.direction() == Direction.CREDIT ? leg.amount().amountMinor()
                            : -leg.amount().amountMinor(), Long::sum);
        }
        for (Map.Entry<String, Long> currency : perCurrency.entrySet()) {
            if (currency.getValue() != 0L) {
                throw new IllegalStateException("structural violation: entry "
                        + posting.transactionKey() + " is unbalanced in " + currency.getKey()
                        + " (net " + currency.getValue() + " minor)");
            }
        }
        if (posting.entryType() == EntryType.REVERSAL) {
            if (posting.reversesEntryId() == null
                    || !entriesById.containsKey(posting.reversesEntryId())) {
                throw new IllegalStateException("reversal pairing violation: entry "
                        + posting.transactionKey() + " compensates unknown entry "
                        + posting.reversesEntryId());
            }
        }
    }

    /**
     * Applies legs to the balances; returns a human description of the first
     * wallet-account overdraft (business rejection), or {@code null} when the
     * entry is applicable.
     */
    private String applyLegs(List<Leg> legs, boolean structuralOverdraftThrows) {
        for (Leg leg : legs) {
            long delta = leg.direction() == Direction.CREDIT ? leg.amount().amountMinor()
                    : -leg.amount().amountMinor();
            long after = balances.computeIfAbsent(leg.accountRef(), ignored ->
                    new ConcurrentHashMap<>()).merge(leg.amount().currency(), delta, Long::sum);
            if (after < 0) {
                if (isWalletAccount(leg.accountRef())) {
                    return "wallet account " + leg.accountRef() + " would be " + after + " "
                            + leg.amount().currency() + " after " + leg.direction().wireName()
                            + " of " + leg.amount().amountMinor();
                }
                if (structuralOverdraftThrows) {
                    throw new IllegalStateException("structural violation: internal account "
                            + leg.accountRef() + " went negative (" + after + " "
                            + leg.amount().currency() + ") — money shape is wrong");
                }
            }
        }
        return null;
    }

    private RecordedEntry record(LedgerPosting posting) {
        UUID entryId = new UUID(0L, 10_000L + uuidSeq.incrementAndGet());
        RecordedEntry entry = new RecordedEntry(entryId, posting.transactionKey(),
                posting.sourceRef(), posting.entryType(), posting.reversesEntryId(),
                posting.reason(), List.copyOf(posting.legs()));
        entriesByKey.put(posting.transactionKey(), entry);
        entriesById.put(entryId, entry);
        journal.add(entry);
        return entry;
    }

    private Source sourceOf(String transactionKey) {
        for (Source source : Source.values()) {
            if (transactionKey.startsWith(source.wireName() + ":")) {
                return source;
            }
        }
        throw new IllegalStateException("transaction key " + transactionKey
                + " does not start with a known source");
    }

    private static List<Leg> inverted(List<Leg> legs) {
        return legs.stream()
                .map(leg -> new Leg(leg.accountRef(), leg.direction().opposite(), leg.amount()))
                .toList();
    }

    private static boolean isWalletAccount(String accountRef) {
        // wallet ledger accounts are UUID strings; internal accounts are
        // "payouts-clearing:KES" style refs
        return accountRef.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }
}
