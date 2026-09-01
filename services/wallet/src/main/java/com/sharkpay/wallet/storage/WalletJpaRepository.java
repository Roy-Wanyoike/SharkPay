package com.sharkpay.wallet.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link WalletEntity}.
 */
public interface WalletJpaRepository extends JpaRepository<WalletEntity, String> {

    Optional<WalletEntity> findByPrincipalIdAndCurrency(UUID principalId, String currency);

    Optional<WalletEntity> findByLedgerAccountId(UUID ledgerAccountId);
}
