package com.sharkpay.reconciliation.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A reconciliation window. The comparison interval is <b>half-open
 * {@code [from, to)}</b> — a line with {@code occurredAt == from} is inside
 * the window, a line with {@code occurredAt == to} is outside. This matches
 * the providers gateway's {@code provider.Window} semantics
 * (services/providers: "Window is a reconciliation window [From, To)").
 *
 * <p>Both ends are mandatory and {@code from} must be strictly before
 * {@code to} (the providers gateway enforces the same).</p>
 */
public record ReconWindow(Instant from, Instant to) {

    public ReconWindow {
        Objects.requireNonNull(from, "window from is required");
        Objects.requireNonNull(to, "window to is required");
        if (!from.isBefore(to)) {
            throw new InvalidWindowException(
                    "window from (" + from + ") must be strictly before to (" + to + ")");
        }
    }

    /** True when {@code occurredAt} is inside this window ([from, to)). */
    public boolean contains(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        return !occurredAt.isBefore(from) && occurredAt.isBefore(to);
    }

    /** Canonical fingerprint part (idempotency conflict detection). */
    public String canonical() {
        return from + "|" + to;
    }
}
