package com.sharkpay.payouts.api.dto;

/**
 * The report of one scheduler tick (POST /internal/payouts/scheduler/tick):
 * how many payouts the release batch submitted, how many were parked for
 * backoff retry, how many exhausted their retries into terminal FAILED, how
 * many the TTL sweep cancelled, and how many provider polls were evaluated.
 */
public record InternalReportJson(int release_considered, int release_submitted, int release_retried,
                                 int release_failed, int expired_cancelled, int polls_evaluated) {

    public static InternalReportJson empty() {
        return new InternalReportJson(0, 0, 0, 0, 0, 0);
    }
}
