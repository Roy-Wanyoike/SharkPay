package com.sharkpay.payouts.events;

import com.sharkpay.payouts.api.dto.MoneyJson;
import com.sharkpay.payouts.domain.Transfer;

import java.time.Instant;

/**
 * Event factories for the transfer event types (source
 * {@value com.sharkpay.payouts.events.CloudEvent#TRANSFERS_SOURCE} — the
 * transfers domain is owned by this service, per
 * contracts/events/transfers.transfer.v1.json). Payload field names use
 * snake_case exactly as the schema specifies (validated against
 * {@code additionalProperties: false} in TransferEventsTest):
 *
 * <ul>
 *   <li>{@code transfers.transfer.succeeded.v1} — internal transfer
 *       committed (the ledger journal entry id is always present);</li>
 *   <li>{@code transfers.transfer.failed.v1} — pre-flight/ledger rejection
 *       that never partially posted (reason present).</li>
 * </ul>
 */
public final class TransferEvents {

    public static final String SUCCEEDED = "transfers.transfer.succeeded.v1";
    public static final String FAILED = "transfers.transfer.failed.v1";

    private TransferEvents() {
    }

    /** Builds the {@code transfers.transfer.succeeded.v1} event (committed). */
    public static CloudEvent succeeded(Transfer transfer, Instant occurredAt) {
        return envelope(SUCCEEDED, transfer, occurredAt, new TransferEventData(transfer.id(),
                transfer.state().wireName(), MoneyJson.of(transfer.amount()),
                MoneyJson.of(transfer.fee()), transfer.sourceWalletId(),
                transfer.destinationWalletId(),
                transfer.entryId() == null ? null : transfer.entryId().toString(), null));
    }

    /** Builds the {@code transfers.transfer.failed.v1} event (rejected). */
    public static CloudEvent failed(Transfer transfer, Instant occurredAt) {
        return envelope(FAILED, transfer, occurredAt, new TransferEventData(transfer.id(),
                transfer.state().wireName(), MoneyJson.of(transfer.amount()),
                MoneyJson.of(transfer.fee()), transfer.sourceWalletId(),
                transfer.destinationWalletId(), null, transfer.failureReason()));
    }

    private static CloudEvent envelope(String type, Transfer transfer, Instant occurredAt,
                                       TransferEventData data) {
        return new CloudEvent(EventIds.uuidV7().toString(), type, CloudEvent.SPECVERSION,
                CloudEvent.TRANSFERS_SOURCE, transfer.id(), occurredAt, data);
    }

    /**
     * Payload of every transfer event (schema: transferData). The ledger
     * journal entry id is present on succeeded events; the reason on failed
     * events.
     */
    public record TransferEventData(String transfer_id, String state, MoneyJson amount,
                                    MoneyJson fee, String source_wallet, String destination_wallet,
                                    String entry_id, String reason) {
    }
}
