package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.api.dto.CompensationJson;
import com.sharkpay.reconciliation.api.dto.CompensationListJson;
import com.sharkpay.reconciliation.api.dto.ProposeCompensationRequest;
import com.sharkpay.reconciliation.api.dto.ReconBreakJson;
import com.sharkpay.reconciliation.api.dto.ReconBreakListJson;
import com.sharkpay.reconciliation.api.dto.TransitionBreakRequest;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;
import com.sharkpay.reconciliation.service.GetBreakUseCase;
import com.sharkpay.reconciliation.service.ListBreaksUseCase;
import com.sharkpay.reconciliation.service.ProposeCompensationUseCase;
import com.sharkpay.reconciliation.service.TransitionBreakUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal recon-break surface (the console + the RB-7 workflow):
 *
 * <pre>
 *   GET   /internal/recon/breaks?state=&aging=&provider=   list breaks (live aging)
 *   GET   /internal/recon/breaks/{id}                      break detail (live aging)
 *   POST  /internal/recon/breaks/{id}/transitions          OPEN→INVESTIGATING→RESOLVED/WAIVED
 *   POST  /internal/recon/breaks/{id}/compensations        propose compensation (operator A)
 * </pre>
 */
@RestController
public final class ReconBreaksController {

    private final GetBreakUseCase getBreak;
    private final ListBreaksUseCase listBreaks;
    private final TransitionBreakUseCase transitionBreak;
    private final ProposeCompensationUseCase proposeCompensation;
    private final CompensationEntryRepository compensations;

    public ReconBreaksController(GetBreakUseCase getBreak, ListBreaksUseCase listBreaks,
                                 TransitionBreakUseCase transitionBreak,
                                 ProposeCompensationUseCase proposeCompensation,
                                 CompensationEntryRepository compensations) {
        this.getBreak = getBreak;
        this.listBreaks = listBreaks;
        this.transitionBreak = transitionBreak;
        this.proposeCompensation = proposeCompensation;
        this.compensations = compensations;
    }

    /** Breaks filtered by state, live aging bucket and/or provider. */
    @GetMapping("/internal/recon/breaks")
    public ReconBreakListJson list(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "aging", required = false) String aging,
            @RequestParam(value = "provider", required = false) String provider) {
        return ReconBreakListJson.of(listBreaks.list(state, aging, provider));
    }

    /** One break with its both-side facts and live aging. */
    @GetMapping("/internal/recon/breaks/{id}")
    public ReconBreakJson get(@PathVariable("id") String id) {
        return ReconBreakJson.of(getBreak.get(id));
    }

    /** Manual lifecycle transition (investigating, resolved, waived). */
    @PostMapping("/internal/recon/breaks/{id}/transitions")
    public ReconBreakJson transition(@PathVariable("id") String id,
                                     @Valid @RequestBody TransitionBreakRequest request) {
        return ReconBreakJson.of(transitionBreak.transition(id, request.to(), request.principal(),
                request.note()));
    }

    /**
     * Operator A drafts the compensation (RB-7 step 2). Idempotent on the
     * Idempotency-Key (201; replay 201 + X-Idempotent-Replay: true).
     */
    @PostMapping("/internal/recon/breaks/{id}/compensations")
    public ResponseEntity<CompensationJson> propose(
            @RequestHeader(value = ReconRunsController.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @PathVariable("id") String breakId,
            @Valid @RequestBody ProposeCompensationRequest request) {
        ProposeCompensationUseCase.Result result = proposeCompensation.propose(idempotencyKey,
                breakId, request.requester(), request.reason(), request.domainLegs(),
                request.reverses_entry_id());
        return ReconRunsController.respond(result.replay(), CompensationJson.of(result.entry()));
    }

    /** The compensations drafted for one break, in proposal order. */
    @GetMapping("/internal/recon/breaks/{id}/compensations")
    public CompensationListJson compensations(@PathVariable("id") String breakId) {
        return CompensationListJson.of(compensations.listByBreak(breakId));
    }
}
