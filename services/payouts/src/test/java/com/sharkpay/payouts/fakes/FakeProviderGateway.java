package com.sharkpay.payouts.fakes;

import com.sharkpay.payouts.ports.ProviderGatewayPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted {@link ProviderGatewayPort} fake mirroring the uniform Provider
 * semantics (services/providers/internal/provider/provider.go):
 * <ul>
 *   <li>{@code initiate} is idempotent on the payouts transaction key — one
 *       rail effect per key, retries are safe end-to-end (SECURITY §4);</li>
 *   <li>{@code poll} returns the scripted status per provider ref (default
 *       {@code PENDING});</li>
 *   <li>{@code cancel} succeeds by default; a scripted refusal throws so the
 *       TTL sweeper parks the payout.</li>
 * </ul>
 * Attempts and effects are counted separately so tests can pin
 * "exactly-once submission" and bounded retries. Executable spec for the
 * providers REST adapter.
 */
public final class FakeProviderGateway implements ProviderGatewayPort {

    private final Map<String, ProviderRef> initiatedByKey = new ConcurrentHashMap<>();
    private final Map<String, Integer> initiateEffects = new ConcurrentHashMap<>();
    private final Map<String, Integer> initiateFailuresForPayout = new ConcurrentHashMap<>();
    private final Map<String, ProviderStatus> statusByRef = new ConcurrentHashMap<>();
    private final List<InitiateSubmission> initiations = new CopyOnWriteArrayList<>();
    private final List<ProviderRef> polls = new CopyOnWriteArrayList<>();
    private final List<ProviderRef> cancellations = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> refuseNextCancel = new ConcurrentHashMap<>();
    private final List<String> refusalHistory = new CopyOnWriteArrayList<>();
    private final List<String> pollFailures = new CopyOnWriteArrayList<>();
    private final AtomicInteger refSeq = new AtomicInteger();
    private ProviderStatus defaultStatus = ProviderStatus.PENDING;

    // ── scripting knobs ────────────────────────────────────────────────────

    /** The next {@code n} initiations for {@code payoutId} throw (provider down). */
    public void failInitiateFor(String payoutId, int n) {
        initiateFailuresForPayout.merge(payoutId, n, Integer::sum);
    }

    /** Scripts the status returned for a specific provider ref. */
    public void statusFor(ProviderRef ref, ProviderStatus status) {
        statusByRef.put(ref.provider() + ":" + ref.ref(), status);
    }

    /** Scripts the default status for unscripted refs (PENDING initially). */
    public void defaultStatus(ProviderStatus status) {
        this.defaultStatus = status;
    }

    /** Makes polls of this provider ref throw (read failure — next tick retries). */
    public void failPollOf(ProviderRef ref) {
        pollFailures.add(ref.provider() + ":" + ref.ref());
    }

    /** Makes the NEXT cancellation of this ref throw (one-shot provider refusal). */
    public void refuseCancelOf(ProviderRef ref) {
        refuseNextCancel.put(ref.provider() + ":" + ref.ref(), Boolean.TRUE);
    }

    // ── port surface ───────────────────────────────────────────────────────

    @Override
    public ProviderRef initiate(InitiateSubmission command) {
        initiations.add(command);
        Integer failures = initiateFailuresForPayout.get(command.payoutId());
        if (failures != null && failures > 0) {
            initiateFailuresForPayout.put(command.payoutId(), failures - 1);
            throw new IllegalStateException("provider down for payout " + command.payoutId()
                    + " (scripted submission failure)");
        }
        return initiatedByKey.computeIfAbsent(command.transactionKey(), key -> {
            initiateEffects.put(key, initiateEffects.size() + 1);
            return new ProviderRef("honeycoin", "hc_" + String.format("%06d",
                    refSeq.incrementAndGet()));
        });
    }

    @Override
    public ProviderStatus poll(ProviderRef ref) {
        String composite = ref.provider() + ":" + ref.ref();
        polls.add(ref);
        if (pollFailures.contains(composite)) {
            throw new IllegalStateException("poll read failed for " + composite
                    + " (scripted read failure)");
        }
        ProviderStatus scripted = statusByRef.get(composite);
        return scripted == null ? defaultStatus : scripted;
    }

    @Override
    public void cancel(ProviderRef ref) {
        String composite = ref.provider() + ":" + ref.ref();
        if (refuseNextCancel.remove(composite) != null) {
            refusalHistory.add(composite);
            throw new IllegalStateException("provider refused cancellation of " + composite);
        }
        cancellations.add(ref);
    }

    // ── introspection ──────────────────────────────────────────────────────

    /** Every initiation attempt, in order (including failed ones). */
    public List<InitiateSubmission> initiations() {
        return List.copyOf(initiations);
    }

    /** Initiation attempts for one payout id. */
    public int initiateAttemptsFor(String payoutId) {
        return (int) initiations.stream()
                .filter(command -> command.payoutId().equals(payoutId)).count();
    }

    /** Rail effects (accepted submissions) per transaction key: 0 or 1. */
    public int initiateEffectsFor(String transactionKey) {
        return initiatedByKey.containsKey(transactionKey) ? 1 : 0;
    }

    /** The provider ref assigned to a transaction key, when submitted. */
    public ProviderRef refOf(String transactionKey) {
        return initiatedByKey.get(transactionKey);
    }

    /** Poll invocations, in order. */
    public List<ProviderRef> polls() {
        return List.copyOf(polls);
    }

    /** Cancellation invocations, in order. */
    public List<ProviderRef> cancellations() {
        return List.copyOf(cancellations);
    }

    /** Whether a cancellation was refused for this composite ref (history). */
    public boolean cancellationWasRefused(ProviderRef ref) {
        return refusalHistory.contains(ref.provider() + ":" + ref.ref());
    }
}
