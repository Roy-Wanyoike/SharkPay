package com.sharkpay.fx.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for {@link ConversionEntity}. */
public interface ConversionJpaRepository extends JpaRepository<ConversionEntity, UUID> {

    Optional<ConversionEntity> findByConversionId(String conversionId);

    List<ConversionEntity> findAllByOrderByCreatedAtAscConversionIdAsc();

    /** Distinct source currencies over all conversions (reconciliation scope). */
    @Query("select distinct c.sourceCurrency from ConversionEntity c")
    List<String> findDistinctSourceCurrencies();

    /** Distinct target currencies over all conversions (reconciliation scope). */
    @Query("select distinct c.targetCurrency from ConversionEntity c")
    List<String> findDistinctTargetCurrencies();
}
