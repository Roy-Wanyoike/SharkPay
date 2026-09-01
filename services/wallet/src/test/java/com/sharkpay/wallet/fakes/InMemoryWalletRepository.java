package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.WalletRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory wallet repository (in-tree test fake (src/test, per ADR 003)).
 * Uniqueness (principal x currency, ledger account) and id-ordered
 * pagination mirror the {@code wallets} table constraints.
 */
public final class InMemoryWalletRepository implements WalletRepository {

    private final Map<String, Wallet> byId = new ConcurrentHashMap<>();

    @Override
    public Wallet save(Wallet wallet) {
        byId.put(wallet.id(), wallet);
        return wallet;
    }

    @Override
    public Optional<Wallet> findById(String walletId) {
        return Optional.ofNullable(walletId == null ? null : byId.get(walletId));
    }

    @Override
    public Optional<Wallet> findByPrincipalAndCurrency(UUID principalId, String currency) {
        return byId.values().stream()
                .filter(wallet -> wallet.principalId().equals(principalId)
                        && wallet.currency().equals(currency == null ? null : currency.toUpperCase(Locale.ROOT)))
                .findFirst();
    }

    @Override
    public Optional<Wallet> findByLedgerAccountId(UUID ledgerAccountId) {
        return byId.values().stream()
                .filter(wallet -> wallet.ledgerAccountId().equals(ledgerAccountId))
                .findFirst();
    }

    @Override
    public List<Wallet> list(WalletFilter filter, int limit, String cursor) {
        return byId.values().stream()
                .filter(wallet -> matches(filter, wallet))
                .filter(wallet -> cursor == null || wallet.id().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(Wallet::id))
                .limit(Math.max(0, limit))
                .toList();
    }

    private static boolean matches(WalletFilter filter, Wallet wallet) {
        if (filter == null) {
            return true;
        }
        if (filter.principalId() != null && !filter.principalId().equals(wallet.principalId())) {
            return false;
        }
        if (filter.currency() != null
                && !filter.currency().toUpperCase(Locale.ROOT).equals(wallet.currency())) {
            return false;
        }
        return filter.status() == null || filter.status() == wallet.status();
    }

    /** Number of stored wallets. */
    public int count() {
        return byId.size();
    }
}
