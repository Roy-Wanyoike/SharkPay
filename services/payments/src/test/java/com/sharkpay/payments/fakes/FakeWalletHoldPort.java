package com.sharkpay.payments.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted {@link WalletHoldPort} fake with the port's full idempotency
 * contract: place/release/capture are keyed by {@code sourceRef} —
 * re-invoking is a no-op that returns/keeps the original outcome, and a
 * second capture for the same sourceRef is a no-op. Call counters separate
 * *attempts* (use-case invocations) from *effects* (actual holds placed /
 * released / captured) so money-safety tests can assert "exactly once".
 * Executable spec for the real wallet funds-control REST adapter.
 */
public final class FakeWalletHoldPort implements WalletHoldPort {

    private final Set<String> knownWallets = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> holdIdBySource = new ConcurrentHashMap<>();
    private final Map<UUID, Money> placed = new ConcurrentHashMap<>();
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Money> captured = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final Map<String, Money> amountsByWallet = new ConcurrentHashMap<>();
    private final List<String> unknownWalletProbes = new CopyOnWriteArrayList<>();

    /** Registers a wallet id as existing (walletExists → true). */
    public FakeWalletHoldPort addWallet(String walletId) {
        knownWallets.add(walletId);
        return this;
    }

    @Override
    public boolean walletExists(String walletId) {
        boolean known = knownWallets.contains(walletId);
        if (!known) {
            unknownWalletProbes.add(walletId);
        }
        return known;
    }

    @Override
    public String placeHold(String walletId, Money amount, UUID sourceRef) {
        attempt("place:" + sourceRef);
        return holdIdBySource.computeIfAbsent(sourceRef, key -> {
            String holdId = "hld_" + String.format("%022d", holdIdBySource.size() + 1);
            placed.put(key, amount);
            amountsByWallet.merge(walletId, amount, Money::add);
            return holdId;
        });
    }

    @Override
    public void releaseHold(String holdId, UUID sourceRef) {
        attempt("release:" + sourceRef);
        released.add(sourceRef);
    }

    @Override
    public void captureHold(String holdId, Money amount, UUID sourceRef) {
        attempt("capture:" + sourceRef);
        captured.putIfAbsent(sourceRef, amount);
    }

    private void attempt(String key) {
        attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** Invocations of one operation (attempts, including idempotent replays). */
    public int attemptsOf(String key) {
        return attempts.getOrDefault(key, new AtomicInteger()).get();
    }

    /** Whether a hold was actually placed for the sourceRef. */
    public boolean hasHold(UUID sourceRef) {
        return holdIdBySource.containsKey(sourceRef);
    }

    /** The hold id the wallet assigned for the sourceRef. */
    public String holdIdOf(UUID sourceRef) {
        return holdIdBySource.get(sourceRef);
    }

    /** Effects: holds actually placed (sourceRef → amount). */
    public Map<UUID, Money> placedHolds() {
        return Map.copyOf(placed);
    }

    /** Whether the hold for the sourceRef was released (at least once). */
    public boolean wasReleased(UUID sourceRef) {
        return released.contains(sourceRef);
    }

    /** Effects: holds captured (sourceRef → captured amount). */
    public Map<UUID, Money> capturedHolds() {
        return Map.copyOf(captured);
    }

    /** Total held amount per wallet (effects only). */
    public Money heldOf(String walletId) {
        return amountsByWallet.getOrDefault(walletId, Money.zero("KES"));
    }

    /** Wallet ids probed for existence that were unknown (404 evidence). */
    public List<String> unknownWalletProbes() {
        return List.copyOf(unknownWalletProbes);
    }
}
