package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.WalletRepository;

import java.util.List;
import java.util.Objects;

/**
 * Read-side use-case for wallet listing: id-ordered, cursor-paginated
 * (opaque cursor = last id of the previous page), filterable by principal,
 * currency and status.
 */
public final class ListWalletsUseCase {

    private final WalletRepository wallets;
    private final BalanceReader balances;

    public ListWalletsUseCase(WalletRepository wallets, BalanceReader balances) {
        this.wallets = Objects.requireNonNull(wallets, "walletRepository is required");
        this.balances = Objects.requireNonNull(balances, "balanceReader is required");
    }

    /**
     * @param principalId optional principal filter
     * @param currency    optional currency filter (canonicalised)
     * @param status      optional status filter
     * @param limit       page size (default 20, max 100)
     * @param cursor      last wallet id of the previous page (null = first)
     */
    public Result list(java.util.UUID principalId, String currency,
                       com.sharkpay.wallet.domain.WalletStatus status, Integer limit, String cursor) {
        int pageSize = normalizeLimit(limit);
        List<GetWalletUseCase.WalletWithBalances> items = wallets
                .list(new WalletRepository.WalletFilter(principalId, currency, status),
                        pageSize + 1, cursor)
                .stream()
                .map(wallet -> new GetWalletUseCase.WalletWithBalances(wallet, balances.balancesOf(wallet)))
                .toList();
        boolean hasMore = items.size() > pageSize;
        List<GetWalletUseCase.WalletWithBalances> page = hasMore ? items.subList(0, pageSize) : items;
        String nextCursor = hasMore ? page.get(page.size() - 1).wallet().id() : null;
        return new Result(page, nextCursor);
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1: " + limit);
        }
        return Math.min(limit, 100);
    }

    /** One page of wallets plus the opaque cursor of the next page. */
    public record Result(List<GetWalletUseCase.WalletWithBalances> items, String nextCursor) {
    }
}
