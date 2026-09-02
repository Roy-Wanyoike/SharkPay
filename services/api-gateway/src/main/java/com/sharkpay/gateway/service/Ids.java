package com.sharkpay.gateway.service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Server-assigned request ids for error envelopes and the X-Request-Id
 * header (common.yaml: {@code req_[0-9A-Za-z]+}).
 */
public final class Ids {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {
    }

    public static String requestId() {
        return "req_" + randomToken(20);
    }

    private static String randomToken(int length) {
        StringBuilder token = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            token.append(ALPHABET[ThreadLocalRandom.current().nextInt(ALPHABET.length)]);
        }
        return token.toString();
    }
}
