package com.sharkpay.payments.storage;

import java.io.Serializable;

/**
 * Composite key of {@code payment_state_transitions}: (payment_id, seq) —
 * per-payment monotonic sequence, append-only.
 */
public class PaymentStateTransitionId implements Serializable {

    private String paymentId;
    private long seq;

    public PaymentStateTransitionId() {
    }

    public PaymentStateTransitionId(String paymentId, long seq) {
        this.paymentId = paymentId;
        this.seq = seq;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public long getSeq() {
        return seq;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PaymentStateTransitionId that)) {
            return false;
        }
        return seq == that.seq && paymentId.equals(that.paymentId);
    }

    @Override
    public int hashCode() {
        return 31 * paymentId.hashCode() + Long.hashCode(seq);
    }

    @Override
    public String toString() {
        return "PaymentStateTransitionId[" + paymentId + "#" + seq + "]";
    }
}
