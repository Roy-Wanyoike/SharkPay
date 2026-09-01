package com.sharkpay.risk.fakes;

import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.RuleSetRepository;

/** In-memory rule-set repository: holds the active config, settable per test. */
public final class MutableRuleSetRepository implements RuleSetRepository {

    private volatile RuleSetConfig active;

    public MutableRuleSetRepository(RuleSetConfig initial) {
        this.active = initial;
    }

    @Override
    public RuleSetConfig activeRuleSet() {
        return active;
    }

    public void setActive(RuleSetConfig config) {
        this.active = config;
    }
}
