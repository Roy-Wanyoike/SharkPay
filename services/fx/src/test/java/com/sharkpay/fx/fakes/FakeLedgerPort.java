package com.sharkpay.fx.fakes;

import com.sharkpay.fx.domain.FxDomainException;
import com.sharkpay.fx.domain.Leg;
import com.sharkpay.fx.ports.LedgerLine;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.LedgerStatement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory fake of the Go ledger service's posting API (ADR 003
 * consumer-driven contract): {@code postTransaction} is idempotent per
 * transaction key (a duplicate returns the original entry id and posts
 * nothing new) and {@code getStatement} returns per-account posting lines.
 * Test hooks expose the number of post attempts and the legs posted per key.
 */
public final class FakeLedgerPort implements LedgerPort {

    private final Map<String, List<Leg>> postingsByKey = new LinkedHashMap<>();
    private final Map<String, String> entryIdByKey = new HashMap<>();
    private final Map<String, List<LedgerLine>> linesByAccount = new LinkedHashMap<>();
    private long postAttempts;
    private boolean failNext;

    @Override
    public synchronized String postTransaction(String idempotencyKey, List<Leg> legs) {
        postAttempts++;
        if (failNext) {
            failNext = false;
            throw new FxDomainException("simulated ledger outage");
        }
        String existing = entryIdByKey.get(idempotencyKey);
        if (existing != null) {
            return existing; // idempotent replay — no new lines
        }
        String entryId = UUID.randomUUID().toString();
        for (Leg leg : legs) {
            linesByAccount.computeIfAbsent(leg.accountRef(), account -> new ArrayList<>())
                    .add(new LedgerLine(idempotencyKey, leg.direction(), leg.amountMinor()));
        }
        postingsByKey.put(idempotencyKey, List.copyOf(legs));
        entryIdByKey.put(idempotencyKey, entryId);
        return entryId;
    }

    @Override
    public synchronized LedgerStatement getStatement(String accountRef) {
        return new LedgerStatement(accountRef, linesByAccount.getOrDefault(accountRef, List.of()));
    }

    /** Number of postTransaction invocations (failed or not). */
    public synchronized long postAttempts() {
        return postAttempts;
    }

    /** Legs posted under a transaction key, if any. */
    public synchronized Optional<List<Leg>> legsPostedFor(String idempotencyKey) {
        return Optional.ofNullable(postingsByKey.get(idempotencyKey));
    }

    /** Ledger entry id returned for a transaction key, if any. */
    public synchronized Optional<String> entryIdFor(String idempotencyKey) {
        return Optional.ofNullable(entryIdByKey.get(idempotencyKey));
    }

    /** Test hook: the next postTransaction call throws once. */
    public void failNextPosting() {
        failNext = true;
    }
}
