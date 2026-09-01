package com.sharkpay.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Consistent error body for all non-2xx responses: {"code": "...", "message": "..."}.
 */
public record ErrorResponse(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message) {
}
