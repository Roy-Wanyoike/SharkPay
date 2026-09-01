package com.sharkpay.payments.api.dto;

import com.sharkpay.payments.domain.PaymentIntent;

import java.util.List;

/**
 * GET /payments page (payments.yaml PaymentList): items + opaque
 * {@code next_cursor} (null/absent when there are no more results).
 */
public record PaymentListJson(List<PaymentJson> items, String next_cursor) {

    public static PaymentListJson of(List<PaymentIntent> intents, String nextCursor) {
        return new PaymentListJson(intents.stream().map(PaymentJson::of).toList(),
                nextCursor == null || nextCursor.isBlank() ? null : nextCursor);
    }
}
