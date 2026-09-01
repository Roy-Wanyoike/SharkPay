package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.WalletRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the wallet repository port: delegation + entity mapping
 * (no business logic — the domain owns the rules). Component-scanned
 * production adapter (mirrors the identity service's storage package).
 */
@Repository
public final class JpaWalletRepository implements WalletRepository {

    private final WalletJpaRepository jpa;

    public JpaWalletRepository(WalletJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Wallet save(Wallet wallet) {
        return jpa.findById(wallet.id())
                .map(entity -> {
                    entity.applyDomain(wallet);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(WalletEntity.fromDomain(wallet)).toDomain());
    }

    @Override
    public Optional<Wallet> findById(String walletId) {
        return jpa.findById(walletId).map(WalletEntity::toDomain);
    }

    @Override
    public Optional<Wallet> findByPrincipalAndCurrency(UUID principalId, String currency) {
        return jpa.findByPrincipalIdAndCurrency(principalId,
                        currency == null ? null : currency.toUpperCase(Locale.ROOT))
                .map(WalletEntity::toDomain);
    }

    @Override
    public Optional<Wallet> findByLedgerAccountId(UUID ledgerAccountId) {
        return jpa.findByLedgerAccountId(ledgerAccountId).map(WalletEntity::toDomain);
    }

    @Override
    public List<Wallet> list(WalletFilter filter, int limit, String cursor) {
        return jpa.findAll(Sort.by("id")).stream()
                .filter(entity -> matches(filter, entity))
                .filter(entity -> cursor == null || entity.getId().compareTo(cursor) > 0)
                .limit(Math.max(0, limit))
                .map(WalletEntity::toDomain)
                .toList();
    }

    private static boolean matches(WalletFilter filter, WalletEntity entity) {
        if (filter == null) {
            return true;
        }
        if (filter.principalId() != null && !filter.principalId().equals(entity.getPrincipalId())) {
            return false;
        }
        if (filter.currency() != null
                && !filter.currency().toUpperCase(Locale.ROOT).equals(entity.getCurrency())) {
            return false;
        }
        return filter.status() == null || filter.status().name().equals(entity.getStatus());
    }
}
