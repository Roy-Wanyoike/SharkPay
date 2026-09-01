package com.sharkpay.payments.fakes;

import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.ProviderRejectedException;
import com.sharkpay.payments.ports.ProviderUnavailableException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted {@link ProviderGatewayPort} fake mirroring the semantics of
 * {@code services/providers/internal/provider/provider.go}: initiate is
 * idempotent on the transaction key (one wire effect per key), poll maps the
 * scripted transfer status per ref, and failure knobs inject definitive
 * rejections ({@link ProviderRejectedException}) and transient unavailability
 * ({@link ProviderUnavailableException}). Executable spec for the real Go
 * gateway REST adapter.
 */
public final class FakeProviderGateway implements ProviderGatewayPort {

    private final List<ProviderCandidateView> candidates = new ArrayList<>();
    private final Map<String, ProviderRef> initiatedByTransactionKey = new ConcurrentHashMap<>();
    private final Map<String, TransferStatus> statusByRef = new ConcurrentHashMap<>();
    private final List<QuoteRequest> quotes = new CopyOnWriteArrayList<>();
    private final List<InitiateRequest> initiations = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger initiateAttempts =
            new java.util.concurrent.atomic.AtomicInteger();
    private final List<ProviderRef> polls = new CopyOnWriteArrayList<>();
    private final List<ProviderRef> cancellations = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> quoteRejections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> initiateRejections = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> initiateUnavailabilities = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> pollUnavailabilities = new ConcurrentHashMap<>();
    private final Queue<TransferStatus> pollScript = new ConcurrentLinkedQueue<>();
    private TransferStatus defaultStatus = TransferStatus.PENDING;

    /** Adds a routable candidate (the router's input). */
    public FakeProviderGateway addCandidate(ProviderCandidateView view) {
        candidates.add(view);
        return this;
    }

    /** Drops every candidate (no-route scenarios). */
    public FakeProviderGateway clearCandidates() {
        candidates.clear();
        return this;
    }

    /** The transfer status returned by poll for the given ref. */
    public FakeProviderGateway statusFor(String ref, TransferStatus status) {
        statusByRef.put(ref, status);
        return this;
    }

    /** The status returned for refs without a scripted one. */
    public FakeProviderGateway defaultStatus(TransferStatus status) {
        this.defaultStatus = status;
        return this;
    }

    /**
     * Scripts the statuses served by consecutive poll() calls, in order
     * (deterministic status progression, e.g. PENDING → PROCESSING →
     * SUCCEEDED). Once the script is exhausted poll() falls back to
     * {@link #statusFor}/{@link #defaultStatus}. Failed (unavailable) poll
     * attempts do not consume the script — they throw before it is served.
     */
    public FakeProviderGateway pollScript(TransferStatus first, TransferStatus... rest) {
        pollScript.add(first);
        pollScript.addAll(List.of(rest));
        return this;
    }

    /** Makes quote() reject (unservable) for the given currency. */
    public FakeProviderGateway rejectQuotesFor(String currency, int times) {
        quoteRejections.put(currency, new AtomicInteger(times));
        return this;
    }

    /** Makes initiate() throw a definitive rejection the next N times. */
    public FakeProviderGateway rejectNextInitiations(int times) {
        initiateRejections.put("n", new AtomicInteger(times));
        return this;
    }

    /** Makes initiate() throw transient unavailability the next N times. */
    public FakeProviderGateway unavailableNextInitiations(int times) {
        initiateUnavailabilities.put("n", new AtomicInteger(times));
        return this;
    }

    /** Makes poll() throw transient unavailability the next N times. */
    public FakeProviderGateway unavailableNextPolls(int times) {
        pollUnavailabilities.put("n", new AtomicInteger(times));
        return this;
    }

    @Override
    public List<ProviderCandidateView> candidates() {
        return List.copyOf(candidates);
    }

    @Override
    public Quote quote(QuoteRequest request) {
        quotes.add(request);
        AtomicInteger reject = quoteRejections.get(request.currency());
        if (reject != null && reject.getAndDecrement() > 0) {
            throw new ProviderRejectedException("no liquidity for " + request.currency()
                    + " over " + request.rail());
        }
        return new Quote("quote-" + quotes.size(), request.amountMinor(),
                request.amountMinor(), 0L, request.currency(), null);
    }

    @Override
    public ProviderRef initiate(InitiateRequest request) {
        initiateAttempts.incrementAndGet();
        AtomicInteger reject = initiateRejections.get("n");
        if (reject != null && reject.getAndDecrement() > 0) {
            initiations.add(request);
            throw new ProviderRejectedException("rail rejected: " + request.transactionKey());
        }
        AtomicInteger unavailable = initiateUnavailabilities.get("n");
        if (unavailable != null && unavailable.getAndDecrement() > 0) {
            throw new ProviderUnavailableException("gateway timeout for "
                    + request.transactionKey());
        }
        initiations.add(request);
        return initiatedByTransactionKey.computeIfAbsent(request.transactionKey(), key -> {
            ProviderRef ref = new ProviderRef("honeycoin", "hc_" + key);
            statusByRef.putIfAbsent(ref.ref(), defaultStatus);
            return ref;
        });
    }

    @Override
    public TransferStatus poll(ProviderRef ref) {
        AtomicInteger unavailable = pollUnavailabilities.get("n");
        if (unavailable != null && unavailable.getAndDecrement() > 0) {
            throw new ProviderUnavailableException("poll timeout for " + ref.ref());
        }
        polls.add(ref);
        TransferStatus scripted = pollScript.poll();
        if (scripted != null) {
            return scripted;
        }
        return statusByRef.getOrDefault(ref.ref(), defaultStatus);
    }

    @Override
    public void cancel(ProviderRef ref) {
        cancellations.add(ref);
    }

    /** Quote invocations, in order. */
    public List<QuoteRequest> quotes() {
        return List.copyOf(quotes);
    }

    /** Initiate invocations, in order (successful wire calls only). */
    public List<InitiateRequest> initiations() {
        return List.copyOf(initiations);
    }

    /** Every initiate invocation, including retryable/rejected attempts. */
    public int initiateAttempts() {
        return initiateAttempts.get();
    }

    /** Distinct transaction keys that produced a wire effect. */
    public Map<String, ProviderRef> initiatedByKey() {
        return new LinkedHashMap<>(initiatedByTransactionKey);
    }

    /** Poll invocations, in order. */
    public List<ProviderRef> polls() {
        return List.copyOf(polls);
    }

    /** Whether the poll script still has statuses to serve. */
    public boolean pollScriptExhausted() {
        return pollScript.isEmpty();
    }

    /** Cancel invocations, in order. */
    public List<ProviderRef> cancellations() {
        return List.copyOf(cancellations);
    }

    /** Convenience: the default V1 HoneyCoin-like candidate view. */
    public static ProviderCandidateView honeycoin() {
        return new ProviderCandidateView("honeycoin", List.of("honeycoin"),
                List.of("KES", "USDC"), List.of("KE"), 40, 300, 9_900, false, 0, 1L, null);
    }
}
