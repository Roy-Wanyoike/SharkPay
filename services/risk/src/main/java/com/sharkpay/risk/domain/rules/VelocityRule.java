package com.sharkpay.risk.domain.rules;

import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.VelocityPolicy;
import com.sharkpay.risk.ports.VelocityCounterStore;

/**
 * Velocity rule: at most {@code N} transactions per sliding window.
 *
 * Ordering contract (important): the engine checks the counter and only the
 * use-case layer {@link VelocityCounterStore#record records} the transaction
 * AFTER the final decision is ALLOW — denied/reviewed transactions never
 * count, and a denied 11th transaction does not extend the window.
 */
public final class VelocityRule implements Rule {

    public static final String ID = "velocity_window";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        VelocityPolicy policy = config.velocity();
        int recent = counters.countInWindow(request.subjectPrincipalId(), policy.window());
        if (recent >= policy.maxTransactions()) {
            return new RuleResult(ID, Outcome.DENY,
                    "velocity exceeded: " + recent + " transactions in the last " + policy.window()
                            + " (max " + policy.maxTransactions() + ")");
        }
        return new RuleResult(ID, Outcome.PASS,
                "velocity ok: " + recent + "/" + policy.maxTransactions() + " in the last " + policy.window());
    }
}
