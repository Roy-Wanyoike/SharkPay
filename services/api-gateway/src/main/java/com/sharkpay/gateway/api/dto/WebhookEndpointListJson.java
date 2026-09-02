package com.sharkpay.gateway.api.dto;

import java.util.List;

/** Cursor-paginated list of webhook endpoints (webhooks.yaml WebhookEndpointList). */
public record WebhookEndpointListJson(List<WebhookEndpointJson> items, String next_cursor) {
}
