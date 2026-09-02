package com.sharkpay.payouts.storage;

import com.sharkpay.payouts.domain.PayoutState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data surface of the {@code payouts} table. The scheduler queries
 * mirror the partial indexes of V1__payouts_init.sql (release-due, expiry
 * sweep, in-flight polling).
 */
public interface PayoutJpaRepository extends JpaRepository<PayoutEntity, String> {

    long countByState(PayoutState state);

    @Query("""
            SELECT p FROM PayoutEntity p
            WHERE p.state = com.sharkpay.payouts.domain.PayoutState.PENDING_RISK
              AND (p.executeAfter IS NULL OR p.executeAfter <= :now)
              AND (p.nextAttemptAt IS NULL OR p.nextAttemptAt <= :now)
            ORDER BY p.executeAfter ASC, p.id ASC
            """)
    List<PayoutEntity> findDueForRelease(@Param("now") Instant now,
                                         org.springframework.data.domain.Limit limit);

    @Query("""
            SELECT p FROM PayoutEntity p
            WHERE p.state IN (com.sharkpay.payouts.domain.PayoutState.PENDING_RISK,
                              com.sharkpay.payouts.domain.PayoutState.PROCESSING)
              AND p.expiresAt < :now
            ORDER BY p.expiresAt ASC, p.id ASC
            """)
    List<PayoutEntity> findExpired(@Param("now") Instant now,
                                   org.springframework.data.domain.Limit limit);

    @Query("""
            SELECT p FROM PayoutEntity p
            WHERE p.state IN (com.sharkpay.payouts.domain.PayoutState.PROCESSING,
                              com.sharkpay.payouts.domain.PayoutState.SENT)
            ORDER BY p.updatedAt ASC, p.id ASC
            """)
    List<PayoutEntity> findInFlight(org.springframework.data.domain.Limit limit);
}
