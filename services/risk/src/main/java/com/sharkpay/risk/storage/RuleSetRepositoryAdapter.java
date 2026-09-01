package com.sharkpay.risk.storage;

import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.RuleSetRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for {@link RuleSetRepository}: the active row with the highest
 * version wins. Falls back to {@link RuleSetConfig#defaults()} until the
 * rule_sets table is seeded (documented bootstrap behavior). Untested
 * locally (ADR 003); RuleSetMapper is unit tested.
 */
@Repository
public class RuleSetRepositoryAdapter implements RuleSetRepository {

    private final RuleSetJpaRepository repo;

    public RuleSetRepositoryAdapter(RuleSetJpaRepository repo) {
        this.repo = repo;
    }

    @Override
    public RuleSetConfig activeRuleSet() {
        return repo.findFirstByActiveTrueOrderByVersionDesc()
                .map(entity -> RuleSetMapper.toDomain(entity.config))
                .orElseGet(RuleSetConfig::defaults);
    }
}
