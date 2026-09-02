package com.sharkpay.gateway.config;

import com.sharkpay.gateway.ports.Randomness;

import java.security.SecureRandom;

/**
 * Production {@link Randomness} adapter: {@link SecureRandom} over the
 * base62 alphabet ({@code 0-9 A-Z a-z}), ~5.95 bits of entropy per char —
 * 43 chars ≈ 256 bits for API key secrets.
 */
public final class SecureRandomRandomness implements Randomness {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final String API_KEY_PREFIX = "sp_live_";
    private static final int SECRET_CHARS = 43;
    private static final int ID_CHARS = 24;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String apiKeySecret() {
        return API_KEY_PREFIX + token(SECRET_CHARS);
    }

    @Override
    public String apiKeyId() {
        return "key_" + token(ID_CHARS);
    }

    @Override
    public String webhookId() {
        return "wh_" + token(ID_CHARS);
    }

    @Override
    public String webhookDeliveryId() {
        return "whd_" + token(ID_CHARS);
    }

    @Override
    public String sandboxPaymentId() {
        return "pay_" + token(ID_CHARS);
    }

    private String token(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return token.toString();
    }
}
