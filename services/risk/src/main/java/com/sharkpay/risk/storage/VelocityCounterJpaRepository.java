package com.sharkpay.risk.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Spring Data repo for velocity_counters rows (minute buckets per subject+currency). */
public interface VelocityCounterJpaRepository extends JpaRepository<VelocityCounterEntity, VelocityCounterId> {

    @Query("select coalesce(sum(v.txnCount), 0) from VelocityCounterEntity v "
            + "where v.subject = :subject and v.windowBucket in :buckets")
    long sumTxnCount(@Param("subject") String subject, @Param("buckets") List<String> buckets);

    @Query("select coalesce(sum(v.amountMinor), 0) from VelocityCounterEntity v "
            + "where v.subject = :subject and v.currency = :currency and v.windowBucket in :buckets")
    long sumAmountMinor(@Param("subject") String subject,
                        @Param("currency") String currency,
                        @Param("buckets") List<String> buckets);
}
