package com.sharkpay.wallet.storage;

import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.ports.HoldRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for the hold repository port (component-scanned production
 * adapter).
 */
@Repository
public final class JpaHoldRepository implements HoldRepository {

    private final HoldJpaRepository jpa;

    public JpaHoldRepository(HoldJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Hold save(Hold hold) {
        return jpa.findById(hold.id())
                .map(entity -> {
                    entity.applyDomain(hold);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(HoldEntity.fromDomain(hold)).toDomain());
    }

    @Override
    public Optional<Hold> findById(String holdId) {
        return jpa.findById(holdId).map(HoldEntity::toDomain);
    }

    @Override
    public List<Hold> findActiveByWalletId(String walletId) {
        return jpa.findByWalletIdAndStateOrderByIdAsc(walletId, HoldState.ACTIVE.name())
                .stream()
                .map(HoldEntity::toDomain)
                .toList();
    }
}
