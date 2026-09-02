package com.sharkpay.payouts.ports;

import com.sharkpay.payouts.domain.Destination;

import java.util.Map;
import java.util.Objects;

/**
 * Consumer-driven port to the providers service gateway (the uniform
 * {@code Provider} abstraction in services/providers/internal/provider —
 * normative per ARCHITECTURE.md §4.2). Mirrors its semantics:
 *
 * <ul>
 *   <li>{@code Initiate} starts a rail transfer with OUR idempotency key
 *       ({@code transactionKey} — the adapter forwards it as the provider
 *       idempotency header so retries are safe end-to-end, SECURITY §4);</li>
 *   <li>{@code Poll} reads the current rail status — unmapped provider
 *       states surface as {@link ProviderStatus#UNKNOWN}, the ambiguity
 *       contract: callers park the payout and raise an ops alert, never
 *       auto-apply a terminal transition and never retry the debit;</li>
 *   <li>{@code Cancel} cancels a transfer the rail has not settled.</li>
 * </ul>
 *
 * <p>Production adapter (REST against the providers service) lands at
 * integration; local tests run the in-tree fake (ADR 003 §3).</p>
 */
public interface ProviderGatewayPort {

    /** Submits a payout to its rail; returns the provider-side reference. */
    ProviderRef initiate(InitiateSubmission command);

    /** Reads the current status of a submitted payout. */
    ProviderStatus poll(ProviderRef ref);

    /** Cancels a not-yet-settled rail transfer (throws on refusal). */
    void cancel(ProviderRef ref);

    /** Rail-agnostic status vocabulary (provider.TransferStatus). */
    enum ProviderStatus {
        /** Rail accepted the request; awaiting settlement. */
        PENDING,
        /** Settlement in flight at the rail. */
        PROCESSING,
        /** Settled at destination (rail-confirmed). */
        SUCCEEDED,
        /** Terminal failure; funds did not move. */
        FAILED,
        /** Funds came back (rail return / provider reversal). */
        RETURNED,
        /** Ambiguous answer: park + alert, never a terminal transition. */
        UNKNOWN
    }

    /**
     * Provider-side transfer identity: the provider name (routing, breaker
     * scopes) and the provider's own transfer id.
     */
    record ProviderRef(String provider, String ref) {

        public ProviderRef {
            Objects.requireNonNull(provider, "provider name is required");
            if (provider.isBlank()) {
                throw new IllegalArgumentException("provider name must not be blank");
            }
            Objects.requireNonNull(ref, "provider ref is required");
            if (ref.isBlank()) {
                throw new IllegalArgumentException("provider ref must not be blank");
            }
        }
    }

    /**
     * An initiation request. {@code transactionKey} is the payouts-derived
     * idempotency key ({@code payouts:pot_...:submit}); metadata passes
     * through with no secrets.
     */
    record InitiateSubmission(String transactionKey, String payoutId, String rail,
                              Destination destination, long amountMinor, String currency,
                              int exponent, Map<String, String> metadata) {

        public InitiateSubmission {
            Objects.requireNonNull(transactionKey, "transactionKey is required");
            Objects.requireNonNull(payoutId, "payoutId is required");
            Objects.requireNonNull(rail, "rail is required");
            Objects.requireNonNull(destination, "destination is required");
            Objects.requireNonNull(currency, "currency is required");
            if (amountMinor <= 0) {
                throw new IllegalArgumentException("amount must be positive: " + amountMinor);
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
