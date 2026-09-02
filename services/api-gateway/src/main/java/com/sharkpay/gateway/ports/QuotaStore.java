package com.sharkpay.gateway.ports;

import com.sharkpay.gateway.domain.QuotaDecision;

import java.time.Instant;

/**
 * Per-key quota store: atomically checks-and-consumes one request against
 * the key's rpm and monthly limits. Windows are UTC-fixed (minute /
 * calendar month, see {@code QuotaWindows}); the store keeps one counter
 * per (key, window) and lets the boundary roll the window.
 */
public interface QuotaStore {

    /**
     * Consumes one request for the key at {@code now} if both windows allow
     * it; otherwise returns the exceeded window with the retry-after
     * seconds. Never consumes on a rejection.
     */
    QuotaDecision checkAndConsume(String keyId, int rpmLimit, long monthlyLimit, Instant now);
}
