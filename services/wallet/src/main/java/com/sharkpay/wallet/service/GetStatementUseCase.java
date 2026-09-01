package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.StatementLine;
import com.sharkpay.wallet.ports.ProjectionStore;
import com.sharkpay.wallet.ports.WalletRepository;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Read-side use-case for the wallet statement: the wallet's projection lines
 * in ledger posting order with running balances (cursor = last posting id).
 */
public final class GetStatementUseCase {

    private final WalletRepository wallets;
    private final ProjectionStore projections;

    public GetStatementUseCase(WalletRepository wallets, ProjectionStore projections) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.projections = Objects.requireNonNull(projections, "projectionStore is required");
    }

    /**
     * @param walletId  the wallet whose statement is requested
     * @param limit     page size (default 20, max 100)
     * @param cursor    last posting id of the previous page (null = first)
     */
    public Result statement(String walletId, Integer limit, String cursor) {
        if (walletId == null || walletId.isBlank()) {
            throw new IllegalArgumentException("wallet id is required");
        }
        wallets.findById(walletId.trim())
                .orElseThrow(() -> new NoSuchElementException("wallet " + walletId + " not found"));
        int pageSize = ListWalletsUseCase.normalizeLimit(limit);
        Long afterPostingId = parseCursor(cursor);
        List<StatementLine> items = projections.statement(walletId.trim(), pageSize + 1,
                afterPostingId);
        boolean hasMore = items.size() > pageSize;
        List<StatementLine> page = hasMore ? items.subList(0, pageSize) : items;
        String nextCursor = hasMore ? String.valueOf(page.get(page.size() - 1).leg().postingId()) : null;
        return new Result(page, nextCursor);
    }

    /** Opaque cursor → posting id (null/blank = first page). */
    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor.trim());
            if (value < 1L) {
                throw new IllegalArgumentException("statement cursor must be a posting id >= 1");
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException("malformed statement cursor: " + cursor, malformed);
        }
    }

    /** One page of statement lines plus the cursor of the next page. */
    public record Result(List<StatementLine> items, String nextCursor) {
    }
}
