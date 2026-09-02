package com.sharkpay.gateway.api.dto;

/**
 * POST /v1/webhook-endpoints/{id}/deliveries/{deliveryId}/replay response:
 * the re-queued delivery (state pending, attempts reset).
 */
public record ReplayAcceptedJson(String delivery_id, String state) {
}
