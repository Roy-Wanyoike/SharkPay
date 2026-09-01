package com.sharkpay.wallet.api.dto;

import com.sharkpay.wallet.domain.Hold;

import java.time.Instant;
import java.util.UUID;

/**
 * Hold JSON: the funds-control reservation with its terminal split
 * (captured + released = amount).
 */
public record HoldJson(String id, String wallet_id, MoneyJson amount, String state,
                       MoneyJson captured_amount, MoneyJson released_amount, String source,
                       UUID source_ref, String reason, Instant created_at, Instant updated_at) {

    public static HoldJson of(Hold hold) {
        return new HoldJson(hold.id(), hold.walletId(), MoneyJson.of(hold.amount()),
                hold.state().wireName(), MoneyJson.of(hold.captured()),
                MoneyJson.of(hold.released()), hold.source().wireName(), hold.sourceRef(),
                hold.reason(), hold.createdAt(), hold.updatedAt());
    }
}
