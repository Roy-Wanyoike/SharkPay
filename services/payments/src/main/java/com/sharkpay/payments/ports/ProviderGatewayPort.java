package com.sharkpay.payments.ports;

import java.util.List;
import java.util.Map;

/**
 * Consumer-driven port (ADR 003 §3) to the Go provider gateway
 * (services/providers). Mirrors the semantics of
 * {@code services/providers/internal/provider/provider.go}:
 *
 * <ul>
 *   <li>{@link #quote} prices a prospective movement (pre-flight capability
 *       and limit check);</li>
 *   <li>{@link #initiate} starts a transfer — {@code transactionKey} is OUR
 *       idempotency key, sent as the provider idempotency header so retries
 *       are safe end-to-end (SECURITY §4 key chain);</li>
 *   <li>{@link #poll} reads the transfer's current rail status; unmapped
 *       provider states return {@link TransferStatus#UNKNOWN} — ambiguity is
 *       a state, not an error: never auto-retry ambiguous debits, park and
 *       keep resolving (the AMBIGUITY CONTRACT in provider.go);</li>
 *   <li>{@link #cancel} cancels an unsettled transfer;</li>
 *   <li>{@link #candidates} lists routable providers (the router's
 *       input).</li>
 * </ul>
 */
public interface ProviderGatewayPort {

    /** Routable provider candidates (capability + cost/health signals). */
    List<ProviderCandidateView> candidates();

    /** Prices a prospective movement (pre-flight check). */
    Quote quote(QuoteRequest request);

    /** Starts a transfer; idempotent on {@code transactionKey}. */
    ProviderRef initiate(InitiateRequest request);

    /** Reads the transfer status at the provider. */
    TransferStatus poll(ProviderRef ref);

    /** Cancels a transfer that has not settled. */
    void cancel(ProviderRef ref);

    // ── value types (provider.go mirrors) ─────────────────────────────────

    /**
     * Rail-agnostic transfer status (provider.go TransferStatus). UNKNOWN
     * means the provider's answer could not be mapped — money may or may not
     * have moved; keep polling / reconcile, never guess.
     */
    enum TransferStatus {
        PENDING, PROCESSING, SUCCEEDED, FAILED, RETURNED, UNKNOWN
    }

    /** A provider candidate as the gateway exposes it for routing. */
    record ProviderCandidateView(String providerId, List<String> rails, List<String> currencies,
                                 List<String> regions, long costBps, long p99Millis,
                                 long successRateBps, boolean breakerOpen, int minTierRank,
                                 Long minTxnMinor, Long maxTxnMinor) {
    }

    /** Identifies one transfer at a specific provider. */
    record ProviderRef(String provider, String ref) {
    }

    /** Quote request: amount, rail and destination of the movement. */
    record QuoteRequest(long amountMinor, String currency, String rail, String destination) {
    }

    /** Quote result: debit / receive / fee (same currency V1: debit = receive + fee). */
    record Quote(String quoteId, long debitMinor, long receiveMinor, long feeMinor,
                 String currency, String expiresAtIso) {
    }

    /** Initiate request; {@code transactionKey} is the adapter-level idempotency key. */
    record InitiateRequest(String transactionKey, long amountMinor, String currency, String rail,
                           String destination, Map<String, String> metadata) {
    }
}
