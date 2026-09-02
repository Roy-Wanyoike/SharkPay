package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationLeg;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A compensation entry on the wire: the 4-eyes principals, the drafted
 * legs, the ledger key, and — after execution — the journal entry id (the
 * audit link).
 */
public record CompensationJson(String id, String break_id, String provider,
                               String compensation_key, String state, String requester,
                               String approver, String reason, List<LegJson> legs,
                               UUID reverses_entry_id, UUID ledger_entry_id, Instant executed_at,
                               boolean ledger_replay) {

    public static CompensationJson of(CompensationEntry entry) {
        return new CompensationJson(entry.id(), entry.breakId(), entry.provider(),
                entry.compensationKey(), entry.state().wireName(), entry.requester(),
                entry.approver(), entry.reason(),
                entry.legs().stream().map(LegJson::of).toList(), entry.reversesEntryId(),
                entry.ledgerEntryId(), entry.executedAt(), entry.ledgerReplay());
    }

    /** One leg: account, side, integer-minor-unit amount. */
    public record LegJson(String account_ref, String direction, MoneyJson amount) {

        static LegJson of(CompensationLeg leg) {
            return new LegJson(leg.accountRef(), leg.direction().wireName(),
                    MoneyJson.of(leg.amount()));
        }
    }
}
