package com.sharkpay.wallet.api.dto;

import java.util.List;

/**
 * Page of wallets (contracts/openapi/v1/wallets.yaml WalletList):
 * {@code items} plus the opaque {@code next_cursor} when more exist.
 */
public record WalletListJson(List<WalletJson> items, String next_cursor) {
}
