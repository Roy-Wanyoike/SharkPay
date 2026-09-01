package com.sharkpay.payments.ports;

import java.util.UUID;

/**
 * Randomness port (deterministic tests): UUID v7 event ids, public payment
 * ids ({@code pay_...}) and error-envelope request ids
 * ({@code req_...}). Production uses the system-entropy implementation; the
 * test fake is sequential. The port exists so domain/service code never
 * touches {@link System#currentTimeMillis()} or {@link UUID#randomUUID()}
 * directly (replayable unit tests, ADR 003 §3).
 */
public interface Randomness {

    /** Time-ordered UUID v7 (RFC 9562) — event ids (events.md convention). */
    UUID uuidV7();

    /** Public payment intent id matching {@code ^pay_[0-9A-Za-z]{20,}$}. */
    String paymentId();

    /** Error-envelope request id matching {@code ^req_[0-9A-Za-z]+$}. */
    String requestId();
}
