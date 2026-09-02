package com.sharkpay.gateway.domain;

/** Webhook event types outside the catalog or not pattern-shaped (webhooks.yaml 422 {@code invalid_events}). */
public final class InvalidEventTypesException extends GatewayDomainException {

    public InvalidEventTypesException(String message) {
        super(message);
    }
}
