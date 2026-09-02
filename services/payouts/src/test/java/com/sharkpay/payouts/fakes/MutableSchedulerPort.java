package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.ports.SchedulerPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Recording {@link SchedulerPort} fake: release wake-up requests and
 * cancellations are retained so tests can pin the scheduling contract
 * (requestRelease at acceptance, cancelRelease on user cancel) — the
 * polling sweeper remains the safety net (production LoggingSchedulerPort
 * parity).
 */
public final class MutableSchedulerPort implements SchedulerPort {

    /** One requested wake-up. */
    public record ReleaseRequest(String payoutId, Instant executeAfter) {
    }

    private final List<ReleaseRequest> requests = new CopyOnWriteArrayList<>();
    private final List<String> cancelled = new CopyOnWriteArrayList<>();

    @Override
    public void requestRelease(String payoutId, Instant executeAfter) {
        Instant requirePayout = SchedulerPort.requirePayout(payoutId, executeAfter);
        requests.add(new ReleaseRequest(payoutId, requirePayout));
    }

    @Override
    public void cancelRelease(String payoutId) {
        if (payoutId == null) {
            throw new NullPointerException("payoutId is required");
        }
        cancelled.add(payoutId);
    }

    /** All release requests, in request order. */
    public List<ReleaseRequest> requests() {
        return List.copyOf(requests);
    }

    /** The release request of one payout, when scheduled. */
    public ReleaseRequest requestOf(String payoutId) {
        return requests.stream().filter(request -> request.payoutId().equals(payoutId))
                .reduce((first, second) -> second).orElse(null);
    }

    /** Payout ids whose wake-up was cancelled, in order. */
    public List<String> cancellations() {
        return List.copyOf(cancelled);
    }
}
