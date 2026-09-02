package com.sharkpay.gateway.domain;

/** Replay was attempted against a delivery that is not dead (409 state_conflict). */
public final class DeliveryNotReplayableException extends GatewayDomainException {

    private final String deliveryId;
    private final DeliveryState state;

    public DeliveryNotReplayableException(String deliveryId, DeliveryState state) {
        super("delivery " + deliveryId + " is " + state.wireName()
                + " — only dead deliveries can be replayed");
        this.deliveryId = deliveryId;
        this.state = state;
    }

    public String deliveryId() {
        return deliveryId;
    }

    public DeliveryState state() {
        return state;
    }
}
