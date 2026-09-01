package com.sharkpay.fx.storage;

import com.sharkpay.fx.domain.Conversion;
import com.sharkpay.fx.ports.ConversionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * JPA adapter for the conversion repository port: delegation + entity
 * mapping. All-oldest-first ordering (createdAt, then public id) matches
 * the in-tree fake; the involved-currencies projection merges the two
 * distinct-currency queries sorted — the scope of position
 * reconciliation. Component-scanned production adapter.
 */
@Repository
public final class JpaConversionRepository implements ConversionRepository {

    private final ConversionJpaRepository jpa;

    public JpaConversionRepository(ConversionJpaRepository jpa) {
        this.jpa = Objects.requireNonNull(jpa, "conversionJpaRepository is required");
    }

    @Override
    public Conversion save(Conversion conversion) {
        return jpa.findByConversionId(conversion.id())
                .map(entity -> {
                    entity.applyDomain(conversion);
                    return jpa.save(entity).toDomain();
                })
                .orElseGet(() -> jpa.save(ConversionEntity.fromDomain(conversion)).toDomain());
    }

    @Override
    public Optional<Conversion> findById(String conversionId) {
        return jpa.findByConversionId(conversionId).map(ConversionEntity::toDomain);
    }

    @Override
    public List<Conversion> findAll() {
        return jpa.findAllByOrderByCreatedAtAscConversionIdAsc().stream()
                .map(ConversionEntity::toDomain)
                .toList();
    }

    @Override
    public List<String> findInvolvedCurrencies() {
        TreeSet<String> currencies = new TreeSet<>();
        currencies.addAll(jpa.findDistinctSourceCurrencies());
        currencies.addAll(jpa.findDistinctTargetCurrencies());
        return List.copyOf(currencies);
    }
}
