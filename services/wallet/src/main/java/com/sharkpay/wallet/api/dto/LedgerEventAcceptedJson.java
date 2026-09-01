package com.sharkpay.wallet.api.dto;

/**
 * Response of the dev ledger-event ingestion endpoint
 * ({@code POST /internal/ledger-events}): how many legs the projector newly
 * applied (0 for duplicate delivery).
 */
public record LedgerEventAcceptedJson(String event_id, int legs_applied) {
}
