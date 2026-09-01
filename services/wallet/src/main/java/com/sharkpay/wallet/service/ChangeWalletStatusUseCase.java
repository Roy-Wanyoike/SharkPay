package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStatus;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.WalletRepository;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Wallet lifecycle transitions (docs/STATE-MACHINES.md §5):
 * {@code ACTIVE ⇄ FROZEN}, each with a mandatory audit reason (compliance
 * action). Transitions are state-checked — re-freezing a frozen wallet is a
 * 409 conflict, not a silent no-op — and publish
 * {@code wallet.state.changed.v1}.
 */
public final class ChangeWalletStatusUseCase {

    private final WalletRepository wallets;
    private final EventPublisher events;
    private final Clock clock;

    public ChangeWalletStatusUseCase(WalletRepository wallets, EventPublisher events, Clock clock) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.events = Objects.requireNonNull(events, "eventPublisher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Freezes an ACTIVE wallet (blocks new holds; existing holds settle). */
    public Wallet freeze(String walletId, String reason) {
        return transition(walletId, reason, "freeze");
    }

    /** Unfreezes a FROZEN wallet. */
    public Wallet unfreeze(String walletId, String reason) {
        return transition(walletId, reason, "unfreeze");
    }

    private Wallet transition(String walletId, String reason, String verb) {
        if (walletId == null || walletId.isBlank()) {
            throw new IllegalArgumentException("wallet id is required");
        }
        Wallet wallet = wallets.findById(walletId.trim())
                .orElseThrow(() -> new NoSuchElementException("wallet " + walletId + " not found"));
        WalletStatus before = wallet.status();
        if (verb.equals("freeze")) {
            wallet.freeze(reason, clock.instant());
        } else {
            wallet.unfreeze(reason, clock.instant());
        }
        wallets.save(wallet);
        events.publish(WalletEvents.walletStateChanged(wallet, before, reason, clock.instant()));
        return wallet;
    }
}
