package com.sharkpay.risk.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.service.AutoCasePolicy;
import com.sharkpay.risk.service.EvaluateTransaction;
import com.sharkpay.risk.service.GetCase;
import com.sharkpay.risk.service.GetEvaluation;
import com.sharkpay.risk.service.OpenCase;
import com.sharkpay.risk.service.RulesEngine;
import com.sharkpay.risk.service.TransitionCase;

import java.time.Instant;
import java.util.UUID;

/**
 * Assembles the whole hexagon on fakes: the same wiring as
 * {@link com.sharkpay.risk.config.RiskConfiguration} but with deterministic
 * ports (ADR 003: fakes in the test tree). Shared by service and controller
 * tests.
 */
public final class RiskHarness {

    public static final Instant INITIAL_TIME = Instant.parse("2026-09-01T10:00:00Z");

    public final MutableClock clock = new MutableClock(INITIAL_TIME);
    public final InMemoryVelocityCounterStore counters = new InMemoryVelocityCounterStore(clock);
    public final InMemoryEvaluationRepository evaluations = new InMemoryEvaluationRepository();
    public final InMemoryCaseRepository cases = new InMemoryCaseRepository();
    public final RecordingEventPublisher events = new RecordingEventPublisher();
    public final MutableRuleSetRepository ruleSets = new MutableRuleSetRepository(RuleSetConfig.defaults());

    public final RulesEngine engine;
    public final OpenCase openCase;
    public final EvaluateTransaction evaluateTransaction;
    public final GetEvaluation getEvaluation;
    public final GetCase getCase;
    public final TransitionCase transitionCase;

    /** Default wiring: the production rule order and default auto-case policy. */
    public RiskHarness() {
        this(RulesEngine.defaultEngine(), AutoCasePolicy.DEFAULT);
    }

    public RiskHarness(RulesEngine engine, AutoCasePolicy autoCasePolicy) {
        this.engine = engine;
        this.openCase = new OpenCase(cases, events, clock);
        this.evaluateTransaction = new EvaluateTransaction(engine, ruleSets, evaluations,
                counters, events, clock, openCase, autoCasePolicy);
        this.getEvaluation = new GetEvaluation(evaluations);
        this.getCase = new GetCase(cases);
        this.transitionCase = new TransitionCase(cases, events, clock);
    }

    /** A request that is allowed under {@link RuleSetConfig#defaults()}. */
    public EvaluationRequest allowedRequest() {
        return request("f47ac10b-58cc-4372-a567-0e02b2c3d479", KycTier.LIMITED, Money.of(100_00L, "KES"));
    }

    /** A request that is denied under defaults (UNVERIFIED zero daily cap). */
    public EvaluationRequest deniedRequest() {
        return request("0d5c9a1e-7b3f-42a1-9c8d-1a2b3c4d5e6f", KycTier.UNVERIFIED, Money.of(10_00L, "KES"));
    }

    /** A request with a fresh evaluation id (idempotency keys must be unique). */
    public EvaluationRequest request(KycTier tier, Money amount) {
        return request(UUID.randomUUID().toString(), tier, amount);
    }

    public EvaluationRequest request(String evaluationId, KycTier tier, Money amount) {
        return EvaluationRequest.of(evaluationId, "subject-1", PrincipalType.INDIVIDUAL, tier,
                amount, Channel.PAYMENT);
    }

    /** Evaluates {@code n} distinct allowed 100.00 KES transactions. */
    public void recordAllowedTransactions(int n) {
        for (int i = 0; i < n; i++) {
            evaluateTransaction.evaluate(request(KycTier.LIMITED, Money.of(100_00L, "KES")));
        }
    }
}
