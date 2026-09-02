package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.EventAcceptedJson;
import com.sharkpay.gateway.domain.UnknownEventTypeException;
import com.sharkpay.gateway.events.CloudEventEnvelope;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.ports.EventConsumer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Dev/internal event intake: accepts one CloudEvent envelope (the
 * contracts/events/*.json shape, internal versioned topic names) and feeds
 * the webhook dispatcher. In production the NATS/Kafka binding calls the
 * same {@link EventConsumer} (see {@code IntegrationPendingEventSource});
 * this endpoint exists for integration testing and replay tooling.
 *
 * <p>Fail-closed validation: malformed envelopes are 400, topics outside
 * the events.md registry are 422 {@code unknown_event_type}. At-least-once
 * semantics: duplicate event ids are no-ops (delivery idempotency).</p>
 */
@RestController
public final class InternalEventController {

    private final EventConsumer dispatcher;
    private final EnvelopeCodec codec;

    public InternalEventController(EventConsumer dispatcher, EnvelopeCodec codec) {
        this.dispatcher = dispatcher;
        this.codec = codec;
    }

    @PostMapping("/internal/events")
    public ResponseEntity<EventAcceptedJson> ingest(@RequestBody JsonNode body) {
        CloudEventEnvelope envelope = codec.parse(body);
        if (!EventTypeCatalog.isKnownTopic(envelope.type())) {
            throw new UnknownEventTypeException(envelope.type());
        }
        int created = dispatcher.onEvent(envelope);
        return ResponseEntity.accepted()
                .body(new EventAcceptedJson(envelope.id(), envelope.type(), created));
    }
}
