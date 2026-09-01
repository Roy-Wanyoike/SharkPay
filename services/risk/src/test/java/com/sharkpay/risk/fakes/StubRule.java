package com.sharkpay.risk.fakes;

import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.VelocityCounterStore;

/** Configurable rule stub that counts invocations (short-circuit tests). */
public final class StubRule implements Rule {

    private final String id;
    private final Outcome outcome;
    private final String reason;
    private int invocations;

    private StubRule(String id, Outcome outcome, String reason) {
        this.id = id;
        this.outcome = outcome;
        this.reason = reason;
    }

    public static StubRule pass(String id) {
        return new StubRule(id, Outcome.PASS, id + " passed");
    }

    public static StubRule deny(String id) {
        return new StubRule(id, Outcome.DENY, id + " denied");
    }

    public static StubRule review(String id) {
        return new StubRule(id, Outcome.REVIEW, id + " needs review");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        invocations++;
        return new RuleResult(id, outcome, reason);
    }

    public int invocations() {
        return invocations;
    }
}
