package com.sharkpay.payments.config;

import com.sharkpay.money.Money;
import com.sharkpay.payments.ports.WalletHoldPort;

import java.util.UUID;

/**
 * Fail-fast placeholder {@link WalletHoldPort} adapter: funds control
 * requires the wallet service's internal hold API, wired at integration
 * time by the integrator (ADR 003 §3). Refusing loudly means no payment can
 * ever place a phantom hold (or capture without one).
 */
public final class IntegrationPendingWalletHoldPort implements WalletHoldPort {

    @Override
    public boolean walletExists(String walletId) {
        throw notWired("walletExists " + walletId);
    }

    @Override
    public String placeHold(String walletId, Money amount, UUID sourceRef) {
        throw notWired("placeHold " + walletId);
    }

    @Override
    public void releaseHold(String holdId, UUID sourceRef) {
        throw notWired("releaseHold " + holdId);
    }

    @Override
    public void captureHold(String holdId, Money amount, UUID sourceRef) {
        throw notWired("captureHold " + holdId);
    }

    private static IllegalStateException notWired(String operation) {
        return new IllegalStateException("WalletHoldPort adapter is not wired yet: the wallet"
                + " service REST client lands at integration time (ADR 003)."
                + " Cannot " + operation + ".");
    }
}
