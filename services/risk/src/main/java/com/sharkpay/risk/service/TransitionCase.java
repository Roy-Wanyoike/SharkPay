package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseIds;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.exceptions.CaseNotFoundException;
import com.sharkpay.risk.events.RiskEvents;
import com.sharkpay.risk.ports.CaseRepository;
import com.sharkpay.risk.ports.EventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Transition a compliance case. Legal edges only (enforced by the domain
 * aggregate; {@code CLOSED} terminal). Every transition records the acting
 * operator (4-eyes). Closing requires a resolution and emits
 * {@code risk.case.resolved.v1}; intermediate transitions emit no event
 * (contract registry defines topics only for open/resolve — see
 * events.RiskEvents).
 */
@Service
public class TransitionCase {

    private final CaseRepository cases;
    private final EventPublisher events;
    private final Clock clock;

    public TransitionCase(CaseRepository cases, EventPublisher events, Clock clock) {
        this.cases = cases;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param rawCaseId public {@code case_<hex>} id or bare UUID
     * @param target    requested target status
     * @param actor     acting operator id (4-eyes)
     * @param resolution required when closing; defaults to CLEARED by the API
     *                   layer, forbidden otherwise
     */
    public Case transition(String rawCaseId, CaseStatus target, String actor, CaseResolution resolution) {
        UUID id = CaseIds.parse(rawCaseId);
        Case c = cases.findById(id).orElseThrow(() -> new CaseNotFoundException(rawCaseId));
        CaseResolution effectiveResolution = (target == CaseStatus.CLOSED && resolution == null)
                ? CaseResolution.CLEARED
                : resolution;
        c.transitionTo(target, actor, effectiveResolution, clock.instant());
        cases.save(c);
        if (target == CaseStatus.CLOSED) {
            events.publish(RiskEvents.caseResolved(c, effectiveResolution, actor));
        }
        return c;
    }
}
