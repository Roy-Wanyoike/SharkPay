package com.sharkpay.reconciliation.api.dto;

/**
 * The single error envelope used by every endpoint
 * (contracts/openapi/v1/common.yaml ErrorEnvelope).
 */
public record ErrorEnvelope(Error error) {

    public record Error(String code, String message, String request_id, java.util.Map<String, Object> details) {
    }
}
