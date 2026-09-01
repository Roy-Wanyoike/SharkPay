package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.ports.HoldRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory hold repository (in-tree test fake (src/test, per ADR 003)).
 * {@link #findActiveByWalletId} is the hot query behind the held-balance
 * partition (mirrored by the partial index on the {@code holds} table).
 */
public final class InMemoryHoldRepository implements HoldRepository {

    private final Map<String, Hold> byId = new ConcurrentHashMap<>();

    @Override
    public Hold save(Hold hold) {
        byId.put(hold.id(), hold);
        return hold;
    }

    @Override
    public Optional<Hold> findById(String holdId) {
        return Optional.ofNullable(holdId == null ? null : byId.get(holdId));
    }

    @Override
    public List<Hold> findActiveByWalletId(String walletId) {
        return byId.values().stream()
                .filter(hold -> hold.walletId().equals(walletId) && hold.state() == HoldState.ACTIVE)
                .sorted(Comparator.comparing(Hold::id))
                .toList();
    }

    /** Number of stored holds (any state). */
    public int count() {
        return byId.size();
    }
}
