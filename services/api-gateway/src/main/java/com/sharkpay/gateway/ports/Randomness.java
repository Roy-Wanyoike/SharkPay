package com.sharkpay.gateway.ports;

/**
 * Cryptographic randomness port (ADR 003 §3): id and secret generation is
 * a seam so tests can be deterministic while production uses
 * {@link java.security.SecureRandom}.
 *
 * <p>Secrets never leave the process except in the exactly-once creation
 * response — only their SHA-256 hash is persisted.</p>
 */
public interface Randomness {

    /**
     * A fresh API key plaintext secret: {@code sp_live_} + 43 base62 chars
     * (docs/SECURITY.md §2 prefix format; 51 chars total, ~256 bits).
     */
    String apiKeySecret();

    /** A fresh API key id: {@code key_} + 24 base62 chars. */
    String apiKeyId();

    /** A fresh webhook endpoint id: {@code wh_} + 24 base62 chars. */
    String webhookId();

    /** A fresh webhook delivery id: {@code whd_} + 24 base62 chars. */
    String webhookDeliveryId();

    /** A fresh sandbox payment id: {@code pay_} + 24 base62 chars. */
    String sandboxPaymentId();
}
