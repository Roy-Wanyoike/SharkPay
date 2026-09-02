package com.sharkpay.payouts.service;

import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * In-flight provider polling: PROCESSING/SENT payouts, oldest-touched
 * first, in bounded batches. Each poll outcome is applied through the same
 * result-application core as the callback ingestion
 * ({@link HandleProviderResultUseCase#apply}), so poll and callback paths
 * cannot disagree about money. Provider-side returns surfaced by polling
 * carry no return reference, so the amount defaults to the full payout.
 */
public final class PollPayoutsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PollPayoutsUseCase.class);

    private final PayoutRepository payouts;
    private final ProviderGatewayPort gateway;
    private final HandleProviderResultUseCase results;
    private final int batchSize;

    public PollPayoutsUseCase(PayoutRepository payouts, ProviderGatewayPort gateway,
                              HandleProviderResultUseCase results, int batchSize) {
        this.payouts = Objects.requireNonNull(payouts, "payoutRepository is required");
        this.gateway = Objects.requireNonNull(gateway, "providerGatewayPort is required");
        this.results = Objects.requireNonNull(results, "handleProviderResultUseCase is required");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /** Polls one in-flight batch and applies every outcome. */
    public Report pollInFlight() {
        List<Payout> inFlight = payouts.findInFlight(batchSize);
        int evaluated = 0;
        for (Payout payout : inFlight) {
            if (payout.state() != PayoutState.PROCESSING && payout.state() != PayoutState.SENT) {
                continue; // raced to terminal between query and poll
            }
            ProviderGatewayPort.ProviderStatus status;
            try {
                status = gateway.poll(ExpirePayoutsUseCase.providerRefOf(payout));
            } catch (RuntimeException pollFailure) {
                log.warn("poll failed for payout {}: {}", payout.id(), pollFailure.getMessage());
                continue; // next tick retries the read — never the debit
            }
            results.apply(payout, status, null, null, null);
            evaluated++;
        }
        return new Report(inFlight.size(), evaluated);
    }

    /** One poll batch: in-flight rows seen / provider outcomes applied. */
    public record Report(int inFlight, int evaluated) {
    }
}
