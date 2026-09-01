package com.sharkpay.payments.domain;

import java.util.Map;
import java.util.Set;

/**
 * Payment intent states (docs/STATE-MACHINES.md §1 — binding). Values are the
 * wire names used by contracts/openapi/v1/payments.yaml PaymentState and the
 * payments.payment.v1.json event schema; the enum is additive-only from now
 * on (values append, never rename).
 *
 * <p>Legal transitions (any transition not listed here is a bug):</p>
 * <pre>
 * CREATED ──risk hold ok──► PENDING_PROVIDER ──provider accepted──► PROCESSING
 *    │                            │        │                          │
 *    │ risk deny                  │ expiry │ provider reject          │ rail confirms
 *    ▼                            ▼        ▼                          ▼
 * BLOCKED                      EXPIRED   FAILED                   SUCCEEDED
 *                                            │                          │
 *                                            │ ops/provider reversal     │ reversal
 *                                            ▼                          ▼
 *                                       REVERSED(same)              REVERSED
 * CREATED ──user cancel──► CANCELLED
 * </pre>
 *
 * <p>Guards encoded here: expiry only from PENDING_PROVIDER; REVERSED only
 * from SUCCEEDED/FAILED; BLOCKED/CANCELLED/EXPIRED/REVERSED are terminal
 * (SUCCEEDED and FAILED are terminal except for the explicit reversal edge —
 * "monotonicity except explicit reversal edges", §7.1).</p>
 */
public enum PaymentState {

    CREATED("CREATED"),
    PENDING_PROVIDER("PENDING_PROVIDER"),
    PROCESSING("PROCESSING"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED"),
    EXPIRED("EXPIRED"),
    REVERSED("REVERSED"),
    BLOCKED("BLOCKED"),
    CANCELLED("CANCELLED");

    private static final Map<PaymentState, Set<PaymentState>> LEGAL = Map.of(
            CREATED, Set.of(PENDING_PROVIDER, BLOCKED, CANCELLED),
            PENDING_PROVIDER, Set.of(PROCESSING, FAILED, EXPIRED, CANCELLED),
            PROCESSING, Set.of(SUCCEEDED, FAILED),
            SUCCEEDED, Set.of(REVERSED),
            FAILED, Set.of(REVERSED),
            EXPIRED, Set.of(),
            REVERSED, Set.of(),
            BLOCKED, Set.of(),
            CANCELLED, Set.of());

    private final String wireName;

    PaymentState(String wireName) {
        this.wireName = wireName;
    }

    /** The wire value (identical to the enum name; kept for symmetry). */
    public String wireName() {
        return wireName;
    }

    /** Parses the wire value (case-sensitive enum lookup). */
    public static PaymentState fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("payment state is required");
        }
        try {
            return PaymentState.valueOf(wire.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown payment state: " + wire);
        }
    }

    /** Whether {@code to} is a legal transition from this state. */
    public boolean canTransitionTo(PaymentState to) {
        return LEGAL.get(this).contains(to);
    }

    /** Legal successor states (defensive copy, unordered). */
    public Set<PaymentState> legalSuccessors() {
        return Set.copyOf(LEGAL.get(this));
    }

    /**
     * Terminal states: no outgoing transitions except the explicit reversal
     * edge from SUCCEEDED/FAILED (the "REVERSED(same)" column of §1).
     */
    public boolean isTerminal() {
        return LEGAL.get(this).isEmpty();
    }

    /** States from which a payment can still be cancelled by the caller. */
    public boolean isCancellable() {
        return this == CREATED || this == PENDING_PROVIDER;
    }

    /** Whether the money-control saga is still live (a hold may be active). */
    public boolean isSagaLive() {
        return this == CREATED || this == PENDING_PROVIDER || this == PROCESSING;
    }
}
