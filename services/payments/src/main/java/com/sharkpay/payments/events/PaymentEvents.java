package com.sharkpay.payments.events;

import com.sharkpay.payments.api.dto.MoneyJson;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.ports.Randomness;

import java.time.Instant;
import java.util.UUID;

/**
 * Event factories for the six {@code payments.payment.*.v1} event types
 * (contracts/events/payments.payment.v1.json — read-only; validated against
 * its {@code additionalProperties: false} schemas in PaymentEventsTest).
 * One event per transition that has a catalog type (payments.yaml webhook
 * mapping):
 *
 * <ul>
 *   <li>{@code created} — intent accepted (state CREATED);</li>
 *   <li>{@code pending_provider} — handed to provider (hold entry posted);</li>
 *   <li>{@code succeeded} — funds confirmed &amp; captured (capture entry);</li>
 *   <li>{@code failed} — terminal failure (reason + release entry);</li>
 *   <li>{@code expired} — TTL elapsed unconfirmed (release entry);</li>
 *   <li>{@code reversed} — compensation entry posted.</li>
 * </ul>
 *
 * <p>BLOCKED and CANCELLED have no catalog event type in /v1 (the catalog
 * lists exactly the six above); those transitions are audited in
 * {@code payment_state_transitions} only — a topic addition
 * (payments.payment.blocked.v1 / cancelled.v1) is an integration-time
 * append-only contract decision, not this service's to make.</p>
 *
 * <p>Event ids are UUID v7 via the {@link Randomness} port; payload field
 * names are snake_case exactly as the schema specifies ({@code reason},
 * {@code entry_id}, {@code provider_ref} are optional and omitted when
 * null — the mapper serializes with NON_NULL inclusion).</p>
 */
public final class PaymentEvents {

    /** contracts/events/payments.payment.v1.json type enum. */
    public static final String CREATED = "payments.payment.created.v1";
    public static final String PENDING_PROVIDER = "payments.payment.pending_provider.v1";
    public static final String SUCCEEDED = "payments.payment.succeeded.v1";
    public static final String FAILED = "payments.payment.failed.v1";
    public static final String EXPIRED = "payments.payment.expired.v1";
    public static final String REVERSED = "payments.payment.reversed.v1";

    private final Randomness randomness;

    public PaymentEvents(Randomness randomness) {
        this.randomness = randomness;
    }

    /** {@code payments.payment.created.v1} — intent accepted. */
    public CloudEvent created(PaymentIntent intent, Instant occurredAt) {
        return event(CREATED, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                null, null, null));
    }

    /** {@code payments.payment.pending_provider.v1} — handed to provider (hold entry). */
    public CloudEvent pendingProvider(PaymentIntent intent, UUID holdEntryId, Instant occurredAt) {
        return event(PENDING_PROVIDER, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                null, holdEntryId, null));
    }

    /** {@code payments.payment.succeeded.v1} — funds confirmed &amp; captured. */
    public CloudEvent succeeded(PaymentIntent intent, UUID captureEntryId, Instant occurredAt) {
        return event(SUCCEEDED, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                null, captureEntryId, intent.providerRef()));
    }

    /** {@code payments.payment.failed.v1} — terminal failure (reason + release entry). */
    public CloudEvent failed(PaymentIntent intent, String reason, UUID releaseEntryId,
                             Instant occurredAt) {
        return event(FAILED, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                reason, releaseEntryId, intent.providerRef()));
    }

    /** {@code payments.payment.expired.v1} — TTL elapsed unconfirmed. */
    public CloudEvent expired(PaymentIntent intent, String reason, UUID releaseEntryId,
                              Instant occurredAt) {
        return event(EXPIRED, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                reason, releaseEntryId, intent.providerRef()));
    }

    /** {@code payments.payment.reversed.v1} — compensation entry posted. */
    public CloudEvent reversed(PaymentIntent intent, String reason, UUID reversalEntryId,
                               Instant occurredAt) {
        return event(REVERSED, intent, occurredAt, new PaymentData(intent.id(),
                intent.state().wireName(), MoneyJson.of(intent.amount()), MoneyJson.of(intent.fee()),
                intent.destination().internalWalletId().orElse(null), intent.rail().wireName(),
                reason, reversalEntryId, intent.providerRef()));
    }

    private CloudEvent event(String type, PaymentIntent intent, Instant occurredAt,
                             PaymentData data) {
        return new CloudEvent(randomness.uuidV7().toString(), type, CloudEvent.SPECVERSION,
                CloudEvent.SOURCE, intent.id(), occurredAt, data);
    }

    /**
     * Payload of every payments.payment.*.v1 event (schema:
     * payments.payment.v1.json $defs/paymentData). Optional fields are
     * omitted when null.
     */
    public record PaymentData(String payment_id, String state, MoneyJson amount, MoneyJson fee,
                              String destination_wallet, String rail, String reason,
                              UUID entry_id, String provider_ref) {
    }
}
