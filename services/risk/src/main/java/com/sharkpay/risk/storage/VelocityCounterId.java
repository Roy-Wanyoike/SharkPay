package com.sharkpay.risk.storage;

import java.io.Serializable;
import java.util.Objects;

/** Composite key of velocity_counters: (subject, window_bucket, currency). */
public class VelocityCounterId implements Serializable {

    private String subject;
    private String windowBucket;
    private String currency;

    public VelocityCounterId() {
    }

    public VelocityCounterId(String subject, String windowBucket, String currency) {
        this.subject = subject;
        this.windowBucket = windowBucket;
        this.currency = currency;
    }

    public String getSubject() {
        return subject;
    }

    public String getWindowBucket() {
        return windowBucket;
    }

    public String getCurrency() {
        return currency;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VelocityCounterId that)) {
            return false;
        }
        return Objects.equals(subject, that.subject)
                && Objects.equals(windowBucket, that.windowBucket)
                && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subject, windowBucket, currency);
    }

    @Override
    public String toString() {
        return subject + "/" + windowBucket + "/" + currency;
    }
}
