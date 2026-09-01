package com.sharkpay.wallet.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link HoldEntity}.
 */
public interface HoldJpaRepository extends JpaRepository<HoldEntity, String> {

    /** ACTIVE holds of a wallet — their sum is the held partition. */
    List<HoldEntity> findByWalletIdAndStateOrderByIdAsc(String walletId, String state);
}
