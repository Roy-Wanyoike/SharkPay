package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.ports.Randomness;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic {@link Randomness} for tests: sequential, predictable ids
 * and secrets (no real secret material — the values are obviously fake,
 * {@code sp_live_fake...}).
 */
public final class SequentialRandomness implements Randomness {

    private final AtomicLong sequence = new AtomicLong();

    @Override
    public String apiKeySecret() {
        // 51 chars total — same shape as production ("sp_live_" + 43 base62),
        // but obviously fake material (no real secret in tests)
        return "sp_live_fake" + pad(39);
    }

    @Override
    public String apiKeyId() {
        return "key_" + pad(24);
    }

    @Override
    public String webhookId() {
        return "wh_" + pad(24);
    }

    @Override
    public String webhookDeliveryId() {
        return "whd_" + pad(24);
    }

    @Override
    public String sandboxPaymentId() {
        return "pay_" + pad(24);
    }

    private String pad(int length) {
        long value = sequence.incrementAndGet();
        String suffix = Long.toString(value);
        return "0".repeat(Math.max(0, length - suffix.length() - 4)) + "0000" + suffix;
    }
}
