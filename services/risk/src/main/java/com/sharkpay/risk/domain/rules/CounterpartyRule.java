package com.sharkpay.risk.domain.rules;

import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.VelocityCounterStore;

/**
 * Counterparty rule: denies transactions whose {@code counterparty_shark_id}
 * appears on the configurable deny-list (sanctioned/blocked SharkIDs).
 * Default list is empty; a missing counterparty passes.
 */
public final class CounterpartyRule implements Rule {

    public static final String ID = "counterparty_denylist";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        String counterparty = request.counterpartySharkId();
        if (counterparty == null) {
            return new RuleResult(ID, Outcome.PASS, "no counterparty provided");
        }
        if (config.counterpartyDenylist().contains(counterparty)) {
            return new RuleResult(ID, Outcome.DENY, "counterparty " + counterparty + " is deny-listed");
        }
        return new RuleResult(ID, Outcome.PASS, "counterparty " + counterparty + " allowed");
    }
}
