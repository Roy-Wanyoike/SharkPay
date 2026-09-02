package com.sharkpay.payouts.events;

import com.sharkpay.payouts.api.dto.MoneyJson;
import com.sharkpay.payouts.domain.Payout;

import java.time.Instant;

/**
 * Event factories for the payout event types (topic registry,
 * contracts/events/events.md). Payload field names use snake_case exactly
 * as specified by contracts/events/payouts.payout.v1.json (validated
 * against {@code additionalProperties: false} in PayoutEventsTest):
 *
 * <ul>
 *   <li>{@code payouts.payout.created.v1} — accepted (PENDING_RISK);</li>
 *   <li>{@code payouts.payout.processing.v1} — submitted to the provider
 *       (PROCESSING);</li>
 *   <li>{@code payouts.payout.sent.v1} — rail accepted (SENT);</li>
 *   <li>{@code payouts.payout.succeeded.v1} — settled at destination
 *       (SUCCEEDED, capture entry id);</li>
 *   <li>{@code payouts.payout.failed.v1} — failed at rail (reason);</li>
 *   <li>{@code payouts.payout.returned.v1} — returned by rail (reason +
 *       compensation entry id).</li>
 * </ul>
 *
 * <p>Destination details are redacted — only the rail type travels. The
 * optional fields ({@code reason}, {@code entry_id}, {@code provider_ref})
 * are omitted when null (NON_NULL inclusion). BLOCKED and CANCELLED are
 * contract states but have no event type in the append-only registry —
 * no event is emitted for them (documented deviation, integrator
 * decision to add topics).</p>
 */
public final class PayoutEvents {

    public static final String CREATED = "payouts.payout.created.v1";
    public static final String PROCESSING = "payouts.payout.processing.v1";
    public static final String SENT = "payouts.payout.sent.v1";
    public static final String SUCCEEDED = "payouts.payout.succeeded.v1";
    public static final String FAILED = "payouts.payout.failed.v1";
    public static final String RETURNED = "payouts.payout.returned.v1";

    private PayoutEvents() {
    }

    /** Builds the {@code payouts.payout.created.v1} event (on acceptance). */
    public static CloudEvent created(Payout payout, Instant occurredAt) {
        return envelope(CREATED, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), null, null,
                payout.providerRef()));
    }

    /** Builds the {@code payouts.payout.processing.v1} event (on submission). */
    public static CloudEvent processing(Payout payout, Instant occurredAt) {
        return envelope(PROCESSING, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), null, null,
                payout.providerRef()));
    }

    /** Builds the {@code payouts.payout.sent.v1} event (rail accepted). */
    public static CloudEvent sent(Payout payout, Instant occurredAt) {
        return envelope(SENT, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), null, null,
                payout.providerRef()));
    }

    /** Builds the {@code payouts.payout.succeeded.v1} event (settled). */
    public static CloudEvent succeeded(Payout payout, Instant occurredAt) {
        return envelope(SUCCEEDED, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), null,
                payout.settleEntryId() == null ? null : payout.settleEntryId().toString(),
                payout.providerRef()));
    }

    /** Builds the {@code payouts.payout.failed.v1} event (terminal failure). */
    public static CloudEvent failed(Payout payout, Instant occurredAt) {
        return envelope(FAILED, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), payout.failureReason(),
                payout.holdEntryId() == null ? null : payout.holdEntryId().toString(),
                payout.providerRef()));
    }

    /** Builds the {@code payouts.payout.returned.v1} event (compensated). */
    public static CloudEvent returned(Payout payout, Instant occurredAt) {
        return envelope(RETURNED, payout, occurredAt, new PayoutEventData(payout.id(),
                payout.state().wireName(), MoneyJson.of(payout.amount()), MoneyJson.of(payout.fee()),
                payout.sourceWalletId(), payout.destination().type(), payout.returnReason(),
                payout.returnEntryId() == null ? null : payout.returnEntryId().toString(),
                payout.providerRef()));
    }

    private static CloudEvent envelope(String type, Payout payout, Instant occurredAt,
                                       PayoutEventData data) {
        return new CloudEvent(EventIds.uuidV7().toString(), type, CloudEvent.SPECVERSION,
                CloudEvent.PAYOUTS_SOURCE, payout.id(), occurredAt, data);
    }

    /**
     * Payload of every payout event (schema: payoutData). Destination
     * details redacted to {@code destination_type}.
     */
    public record PayoutEventData(String payout_id, String state, MoneyJson amount, MoneyJson fee,
                                  String source_wallet, String destination_type, String reason,
                                  String entry_id, String provider_ref) {
    }
}
