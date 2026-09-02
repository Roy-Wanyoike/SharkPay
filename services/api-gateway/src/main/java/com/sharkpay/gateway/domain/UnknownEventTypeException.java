package com.sharkpay.gateway.domain;

/** An event topic outside the registry (contracts/events/events.md) — 422. */
public final class UnknownEventTypeException extends GatewayDomainException {

    private final String topic;

    public UnknownEventTypeException(String topic) {
        super("unknown event topic: " + topic);
        this.topic = topic;
    }

    public String topic() {
        return topic;
    }
}
