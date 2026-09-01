package com.sharkpay.payments.service;

import java.util.UUID;

/**
 * Static id helper for the error envelope's request ids
 * ({@code ^req_[0-9A-Za-z]+$}). Domain ids (payment ids, event UUID v7) go
 * through the {@link com.sharkpay.payments.ports.Randomness} port so tests
 * are deterministic; error request ids are logging correlation only.
 */
public final class Ids {

    private Ids() {
    }

    public static String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
