package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.domain.QuotaDecision;
import com.sharkpay.gateway.domain.QuotaWindows;
import com.sharkpay.gateway.ports.QuotaStore;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link QuotaStore} fake mirroring the JPA adapter's semantics
 * exactly: one counter per (key, window kind, window start); nothing
 * consumed on rejection; windows roll by row.
 */
public final class InMemoryQuotaStore implements QuotaStore {

    private final Map<String, Long> minuteCounters = new HashMap<>();
    private final Map<String, Long> monthCounters = new HashMap<>();

    @Override
    public QuotaDecision checkAndConsume(String keyId, int rpmLimit, long monthlyLimit,
                                         Instant now) {
        String minuteKey = keyId + "|" + QuotaWindows.minuteStart(now);
        String monthKey = keyId + "|" + QuotaWindows.monthStart(now);
        long minuteUsed = minuteCounters.getOrDefault(minuteKey, 0L);
        long monthUsed = monthCounters.getOrDefault(monthKey, 0L);
        if (minuteUsed >= rpmLimit) {
            return QuotaDecision.perMinuteExceeded(
                    QuotaWindows.retryAfterSeconds(now, QuotaWindows.minuteEnd(now)));
        }
        if (monthUsed >= monthlyLimit) {
            return QuotaDecision.monthlyExceeded(
                    QuotaWindows.retryAfterSeconds(now, QuotaWindows.monthEnd(now)));
        }
        minuteCounters.put(minuteKey, minuteUsed + 1);
        monthCounters.put(monthKey, monthUsed + 1);
        return QuotaDecision.allow();
    }

    /** Test oracle: raw counter map (minute|month keyed). */
    public Map<String, Long> minuteCounters() {
        return Map.copyOf(minuteCounters);
    }
}
