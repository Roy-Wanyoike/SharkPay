package com.sharkpay.wallet.api.dto;

import java.util.Map;

/**
 * The single error envelope used by every endpoint
 * (contracts/openapi/v1/common.yaml ErrorEnvelope).
 */
public record ErrorEnvelope(Error error) {

    public record Error(String code, String message, String request_id, Map<String, Object> details) {
    }
}
