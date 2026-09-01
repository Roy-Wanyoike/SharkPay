package com.sharkpay.risk.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Internal-API error envelope: flat {@code {code, message}}. */
public record ErrorDto(
        @JsonProperty("code") String code,
        @JsonProperty("message") String message) {

    public static ErrorDto of(String code, String message) {
        return new ErrorDto(code, message);
    }
}
