package com.sharkpay.gateway.api.dto;

import java.util.List;

/** Cursor-paginated list of webhook deliveries (newest first). */
public record WebhookDeliveryListJson(List<WebhookDeliveryJson> items, String next_cursor) {
}
