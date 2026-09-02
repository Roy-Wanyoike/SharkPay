package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * TTL-expiry sweep: payouts past their {@code expiresAt} that the provider
 * has not accepted are auto-cancelled (payouts.yaml: "TTL before the payout
 * is auto-cancelled if the provider has not accepted it"). A PROCESSING
 * payout is cancelled at the provider first; a provider cancellation
 * refusal parks it for the next tick (never force-cancel in-flight money).
 * Holds are released via strict ledger reversal.
 */
public final class ExpirePayoutsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpirePayoutsUseCase.class);

    private final PayoutRepository payouts;
    private final ProviderGatewayPort gateway;
    private final LedgerPort ledger;
    private final Clock clock;
    private final int batchSize;

    public ExpirePayoutsUseCase(PayoutRepository payouts, ProviderGatewayPort gateway,
                                LedgerPort ledger, Clock clock, int batchSize) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.gateway = Objects.requireNonNull(gateway, "providerGatewayPort is required");
        this.ledger = Objects.requireNonNull(ledger, "ledgerPort is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** Cancels one expiry batch (bounded by the configured batch size). */
    public Report expireOverdue() {
        List<Payout> expired = payouts.findExpired(clock.instant(), batchSize);
        int cancelled = 0;
        for (Payout payout : expired) {
            if (payout.state() == PayoutState.PROCESSING) {
                try {
                    gateway.cancel(providerRefOf(payout));
                } catch (RuntimeException providerRefusal) {
                    log.warn("payout {} TTL expired but provider cancel refused ({}); "
                            + "parked for the next sweep", payout.id(), providerRefusal.getMessage());
                    continue;
                }
            }
            payout.cancel("ttl expired before provider acceptance", clock.instant(), true);
            HoldReleaser.release(ledger, payout, "payout ttl expired");
            payouts.save(payout);
            cancelled++;
            log.info("payout {} auto-cancelled at TTL", payout.id());
        }
        return new Report(expired.size(), cancelled);
    }

    static ProviderGatewayPort.ProviderRef providerRefOf(Payout payout) {
        String composite = payout.providerRef();
        if (composite == null || composite.isBlank()) {
            throw new IllegalStateException("payout " + payout.id() + " has no provider ref");
        }
        int split = composite.indexOf(':');
        if (split <= 0 || split == composite.length() - 1) {
            throw new IllegalStateException("malformed provider ref: " + composite);
        }
        return new ProviderGatewayPort.ProviderRef(composite.substring(0, split),
                composite.substring(split + 1));
    }

    /** One sweep: considered / cancelled (rest parked on provider refusal). */
    public record Report(int considered, int cancelled) {
    }
}
