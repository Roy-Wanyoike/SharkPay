package com.sharkpay.payments.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.payments.ports.LedgerPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted {@link LedgerPort} fake with the port's idempotency contract:
 * entries are keyed {@code (paymentId, EntryType)} — posting twice returns
 * the original entry id with no second journal effect; reversals are keyed
 * {@code (paymentId, REVERSAL)}. Effect counters separate attempts from
 * journal rows so money-safety tests can assert "compensation exactly once /
 * no double capture". Executable spec for the Go ledger REST adapter.
 */
public final class FakeLedgerPort implements LedgerPort {

    private record EntryKey(UUID paymentId, EntryType type) {
    }

    private final Map<EntryKey, UUID> entries = new ConcurrentHashMap<>();
    private final Map<EntryKey, Money> amounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> reasons = new ConcurrentHashMap<>();
    private final Map<EntryKey, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> reversalsByPayment = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> reversedEntries = new LinkedHashMap<>();
    private final AtomicInteger uuidSeq = new AtomicInteger();

    @Override
    public UUID postEntry(UUID paymentId, EntryType type, String walletId, Money amount,
                          String reason) {
        attempts.computeIfAbsent(new EntryKey(paymentId, type), ignored -> new AtomicInteger())
                .incrementAndGet();
        return entries.computeIfAbsent(new EntryKey(paymentId, type), key -> {
            UUID entryId = new UUID(0L, 1_000_000L + uuidSeq.incrementAndGet());
            amounts.put(key, amount);
            reasons.put(entryId, walletId + "|" + reason);
            return entryId;
        });
    }

    @Override
    public UUID reverseEntry(UUID entryId, UUID paymentId, String reason) {
        return reversalsByPayment.computeIfAbsent(paymentId, key -> {
            UUID reversalId = new UUID(0L, 5_000_000L + uuidSeq.incrementAndGet());
            reversedEntries.put(entryId, reversalId);
            reasons.put(reversalId, "reversal|" + reason);
            return reversalId;
        });
    }

    /** Journal effect count for (paymentId, type) — 0 or 1 by contract. */
    public int effectCount(UUID paymentId, EntryType type) {
        return entries.containsKey(new EntryKey(paymentId, type)) ? 1 : 0;
    }

    /** Attempt count (invocations, including idempotent replays). */
    public int attemptCount(UUID paymentId, EntryType type) {
        return attempts.getOrDefault(new EntryKey(paymentId, type), new AtomicInteger()).get();
    }

    /** The journal entry id for (paymentId, type), when posted. */
    public UUID entryIdOf(UUID paymentId, EntryType type) {
        return entries.get(new EntryKey(paymentId, type));
    }

    /** The amount posted for (paymentId, type), when posted. */
    public Money amountOf(UUID paymentId, EntryType type) {
        return amounts.get(new EntryKey(paymentId, type));
    }

    /** The reversal entry posted for a payment, when reversed. */
    public UUID reversalOf(UUID paymentId) {
        return reversalsByPayment.get(paymentId);
    }

    /** The reversal entry posted against a specific entry id, when reversed. */
    public UUID reversalOfEntry(UUID entryId) {
        return reversedEntries.get(entryId);
    }

    /** Total journal rows posted (hold/release/capture/reversal effects). */
    public int totalEffects() {
        return entries.size() + reversalsByPayment.size();
    }
}
