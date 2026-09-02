package com.sharkpay.reconciliation.service;

import java.util.UUID;

/**
 * Public id generation for the error envelope's request ids
 * ({@code req_[0-9A-Za-z]+}), mirroring the wallet service's convention.
 * All entity/event ids come from the {@code Randomness} port instead.
 */
public final class Ids {

    private Ids() {
    }

    public static String requestId() {
        return "req_" + UUID.randomUUID().toString().replace("-", "");
    }
}
