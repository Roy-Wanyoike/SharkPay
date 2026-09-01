package com.sharkpay.wallet.api.dto;

import java.util.List;

/**
 * Page of statement entries (contracts/openapi/v1/wallets.yaml
 * StatementList).
 */
public record StatementListJson(List<StatementEntryJson> items, String next_cursor) {
}
