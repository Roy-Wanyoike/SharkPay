package com.sharkpay.risk.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repo for rule_sets rows; the active row is the highest active version. */
public interface RuleSetJpaRepository extends JpaRepository<RuleSetEntity, Long> {

    Optional<RuleSetEntity> findFirstByActiveTrueOrderByVersionDesc();
}
