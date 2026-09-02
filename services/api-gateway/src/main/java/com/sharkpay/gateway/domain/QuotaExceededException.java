package com.sharkpay.gateway.domain;

/**
 * The caller's key exceeded its quota (common.yaml 429 quota_exceeded +
 * Retry-After). Carries the window kind and the whole seconds until reset.
 */
public final class QuotaExceededException extends GatewayDomainException {

    private final boolean monthly;
    private final long retryAfterSeconds;

    public QuotaExceededException(boolean monthly, long retryAfterSeconds) {
        super(monthly
                ? "monthly request quota exceeded; retry after " + retryAfterSeconds + "s"
                : "requests-per-minute quota exceeded; retry after " + retryAfterSeconds + "s");
        this.monthly = monthly;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public boolean monthly() {
        return monthly;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
