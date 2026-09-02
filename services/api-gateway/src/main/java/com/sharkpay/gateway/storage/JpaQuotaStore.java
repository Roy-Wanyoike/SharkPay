package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.QuotaDecision;
import com.sharkpay.gateway.domain.QuotaWindows;
import com.sharkpay.gateway.ports.QuotaStore;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * JPA adapter for the quota store: check-and-consume against the current
 * UTC minute and calendar-month windows. Nothing is consumed on a
 * rejection; the window rows roll automatically at their boundaries (a new
 * window start is a new row).
 *
 * <p>Concurrency: single-row upsert semantics; the integration phase adds
 * pessimistic locking ({@code SELECT ... FOR UPDATE}) if multiple gateway
 * replicas serve one key (G5).</p>
 */
@Repository
public final class JpaQuotaStore implements QuotaStore {

    static final String MINUTE = "MINUTE";
    static final String MONTH = "MONTH";

    private final QuotaBucketJpaRepository jpa;

    public JpaQuotaStore(QuotaBucketJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public QuotaDecision checkAndConsume(String keyId, int rpmLimit, long monthlyLimit,
                                         Instant now) {
        Instant minuteStart = QuotaWindows.minuteStart(now);
        Instant minuteEnd = QuotaWindows.minuteEnd(now);
        Instant monthStart = QuotaWindows.monthStart(now);
        Instant monthEnd = QuotaWindows.monthEnd(now);

        QuotaBucketEntity minute = bucket(keyId, MINUTE, minuteStart, minuteEnd, now);
        QuotaBucketEntity month = bucket(keyId, MONTH, monthStart, monthEnd, now);

        if (minute.getUsed() >= rpmLimit) {
            return QuotaDecision.perMinuteExceeded(QuotaWindows.retryAfterSeconds(now, minuteEnd));
        }
        if (month.getUsed() >= monthlyLimit) {
            return QuotaDecision.monthlyExceeded(QuotaWindows.retryAfterSeconds(now, monthEnd));
        }
        minute.setUsed(minute.getUsed() + 1);
        minute.setUpdatedAt(now);
        month.setUsed(month.getUsed() + 1);
        month.setUpdatedAt(now);
        jpa.save(minute);
        jpa.save(month);
        return QuotaDecision.allow();
    }

    private QuotaBucketEntity bucket(String keyId, String kind, Instant start, Instant end,
                                     Instant now) {
        return jpa.findById(new QuotaBucketId(keyId, kind, start))
                .orElseGet(() -> new QuotaBucketEntity(keyId, kind, start, end, now));
    }
}
