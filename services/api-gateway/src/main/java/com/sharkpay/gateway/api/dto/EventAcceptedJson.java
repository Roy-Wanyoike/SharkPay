package com.sharkpay.gateway.api.dto;

/**
 * POST /internal/events response: the accepted envelope id, the resolved
 * public event type and how many new webhook deliveries were created.
 */
public record EventAcceptedJson(String event_id, String type, int deliveries_created) {
}
