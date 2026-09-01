package com.sharkpay.wallet.api.dto;

import com.sharkpay.wallet.domain.StatementLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One wallet statement line (contracts/openapi/v1/wallets.yaml
 * StatementEntry): an immutable posting projection line with its running
 * balance.
 */
public record StatementEntryJson(String id, UUID entry_id, String entry_type, String direction,
                                 MoneyJson amount, MoneyJson balance_after, String source,
                                 UUID source_ref, String reason, Instant created_at) {

    public static StatementEntryJson of(StatementLine line) {
        return new StatementEntryJson(String.valueOf(line.leg().postingId()),
                line.leg().entryId(), line.leg().entryType(), line.leg().direction().wireName(),
                MoneyJson.of(line.leg().amount()), MoneyJson.of(line.balanceAfter()),
                line.leg().source().wireName(), line.leg().sourceRef(), line.leg().reason(),
                line.leg().occurredAt());
    }
}
