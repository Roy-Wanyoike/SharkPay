package com.sharkpay.fx.api;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /fx/convert request body (contracts/openapi/v1/fx.yaml
 * ConversionCreateRequest).
 *
 * <p>{@code source_wallet}/{@code destination_wallet} carry the ledger
 * account refs of the caller's base/quote-currency wallets — the real
 * wallet ids are resolved by the caller/integration layer (this service
 * never looks wallets up).
 */
public record ConversionCreateRequest(
        @NotBlank(message = "is required") String quote_id,
        @NotBlank(message = "is required") String source_wallet,
        @NotBlank(message = "is required") String destination_wallet) {
}
