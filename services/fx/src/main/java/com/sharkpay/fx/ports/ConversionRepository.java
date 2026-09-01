package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Conversion;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for conversions (owned by the FX service).
 */
public interface ConversionRepository {

    Conversion save(Conversion conversion);

    Optional<Conversion> findById(String conversionId);

    /** All conversions, oldest first (deterministic for tests/reconciliation). */
    List<Conversion> findAll();

    /**
     * Distinct currencies involved in any conversion (source and target),
     * sorted — drives position reconciliation scope.
     */
    List<String> findInvolvedCurrencies();
}
