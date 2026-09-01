package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.exceptions.EvaluationConflictException;
import com.sharkpay.risk.events.RiskEvents;
import com.sharkpay.risk.ports.EvaluationRepository;
import com.sharkpay.risk.ports.EventPublisher;
import com.sharkpay.risk.ports.RuleSetRepository;
import com.sharkpay.risk.ports.VelocityCounterStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Optional;

/**
 * Evaluate a transaction (the {@code risk.EvaluatePre/Post} use case).
 *
 * <p>Idempotency: keyed by {@code evaluationId}. A replay of the exact same
 * request returns the original decision — rules do not re-run, velocity
 * counters are not incremented again, and events are not re-published. The
 * same id with a different payload is a conflict (409).</p>
 *
 * <p>Counter ordering (documented contract): the engine reads the windowed
 * counters; only when the final decision is ALLOW does this use case record
 * the transaction in the counters, before persisting the evaluation. Denied
 * and review-parked transactions never count.</p>
 *
 * <p>Side effects: {@code risk.decision.v1} on every first-time evaluation;
 * when the decision triggers the {@link AutoCasePolicy} a compliance case is
 * opened (emitting {@code risk.case.opened.v1}).</p>
 */
@Service
public class EvaluateTransaction {

    private final RulesEngine engine;
    private final RuleSetRepository ruleSets;
    private final EvaluationRepository evaluations;
    private final VelocityCounterStore counters;
    private final EventPublisher events;
    private final Clock clock;
    private final OpenCase openCase;
    private final AutoCasePolicy autoCasePolicy;

    public EvaluateTransaction(RulesEngine engine,
                               RuleSetRepository ruleSets,
                               EvaluationRepository evaluations,
                               VelocityCounterStore counters,
                               EventPublisher events,
                               Clock clock,
                               OpenCase openCase,
                               AutoCasePolicy autoCasePolicy) {
        this.engine = engine;
        this.ruleSets = ruleSets;
        this.evaluations = evaluations;
        this.counters = counters;
        this.events = events;
        this.clock = clock;
        this.openCase = openCase;
        this.autoCasePolicy = autoCasePolicy;
    }

    public Evaluation evaluate(EvaluationRequest request) {
        Optional<Evaluation> existing = evaluations.findById(request.evaluationId());
        if (existing.isPresent()) {
            Evaluation original = existing.get();
            if (!original.request().equals(request)) {
                throw new EvaluationConflictException(request.evaluationId());
            }
            return original;
        }

        RuleSetConfig config = ruleSets.activeRuleSet();
        com.sharkpay.risk.domain.DecisionResult result = engine.evaluate(request, config, counters);

        // Counters increment on ALLOWED evaluations only (documented ordering).
        if (result.decision() == com.sharkpay.risk.domain.Decision.ALLOW) {
            counters.record(request.subjectPrincipalId(), request.amount(), clock.instant());
        }

        Evaluation evaluation = new Evaluation(request.evaluationId(), request, result.decision(),
                result.ruleResults(), clock.instant());
        evaluations.save(evaluation);
        events.publish(RiskEvents.decisionCompleted(evaluation));

        if (autoCasePolicy.opensOn(result.decision())) {
            openCase.open(request.subjectPrincipalId(), autoOpenReason(evaluation));
        }
        return evaluation;
    }

    private static String autoOpenReason(Evaluation evaluation) {
        return "auto-opened by risk evaluation " + evaluation.evaluationId()
                + " (decision=" + evaluation.decision().wire() + ", rules="
                + evaluation.ruleResults().stream()
                        .map(com.sharkpay.risk.domain.RuleResult::ruleId)
                        .toList()
                + ")";
    }
}
