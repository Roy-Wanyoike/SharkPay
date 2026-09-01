package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.events.RiskEvents;
import com.sharkpay.risk.ports.CaseRepository;
import com.sharkpay.risk.ports.EventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Open a compliance case — manually via the API or automatically by
 * {@link EvaluateTransaction} when the auto-case policy fires. Emits
 * {@code risk.case.opened.v1}.
 */
@Service
public class OpenCase {

    private final CaseRepository cases;
    private final EventPublisher events;
    private final Clock clock;

    public OpenCase(CaseRepository cases, EventPublisher events, Clock clock) {
        this.cases = cases;
        this.events = events;
        this.clock = clock;
    }

    public Case open(String subjectPrincipalId, String reason) {
        Case c = Case.open(UUID.randomUUID(), subjectPrincipalId, reason, clock.instant());
        cases.save(c);
        events.publish(RiskEvents.caseOpened(c));
        return c;
    }
}
