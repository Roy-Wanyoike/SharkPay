package com.sharkpay.wallet.ports;

import com.sharkpay.wallet.domain.Hold;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the hold ledger.
 */
public interface HoldRepository {

    Hold save(Hold hold);

    Optional<Hold> findById(String holdId);

    /** ACTIVE holds of a wallet — their sum is the wallet's held partition. */
    List<Hold> findActiveByWalletId(String walletId);
}
