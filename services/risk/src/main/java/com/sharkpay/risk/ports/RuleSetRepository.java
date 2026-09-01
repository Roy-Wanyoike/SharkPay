package com.sharkpay.risk.ports;

import com.sharkpay.risk.domain.RuleSetConfig;

/**
 * Persistence port for rule sets. The active rule set backs every
 * evaluation; implementations fall back to {@link RuleSetConfig#defaults()}
 * until the rule_sets table is seeded (documented bootstrap behavior).
 */
public interface RuleSetRepository {

    RuleSetConfig activeRuleSet();
}
