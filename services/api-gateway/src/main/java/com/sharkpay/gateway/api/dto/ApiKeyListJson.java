package com.sharkpay.gateway.api.dto;

import java.util.List;

/** Cursor-paginated list of API keys (common.yaml pagination shape). */
public record ApiKeyListJson(List<ApiKeyJson> items, String next_cursor) {
}
