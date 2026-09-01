package com.sharkpay.wallet.storage;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Spring Data repository for {@link WalletPostingEntity} (append-only
 * projection lines; ordered by posting id — the ledger's sequence).
 */
public interface WalletPostingJpaRepository extends JpaRepository<WalletPostingEntity, WalletPostingId> {

    /** Lines after {@code afterPostingId} (0 = from the beginning). */
    @Query("select p from WalletPostingEntity p where p.id.walletId = :walletId "
            + "and p.id.postingId > :after order by p.id.postingId asc")
    List<WalletPostingEntity> findNext(@Param("walletId") String walletId,
                                       @Param("after") long afterPostingId, Limit limit);

    /** The latest line (its balance_after is the wallet's total balance). */
    @Query("select p from WalletPostingEntity p where p.id.walletId = :walletId "
            + "order by p.id.postingId desc")
    List<WalletPostingEntity> findLast(@Param("walletId") String walletId, Limit limit);

    /** All lines in posting order (for the ordered balance recompute). */
    @Query("select p from WalletPostingEntity p where p.id.walletId = :walletId "
            + "order by p.id.postingId asc")
    List<WalletPostingEntity> findAllOrdered(@Param("walletId") String walletId);
}
