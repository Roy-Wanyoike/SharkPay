package com.sharkpay.risk.api;

import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.service.EvaluateTransaction;
import com.sharkpay.risk.service.GetCase;
import com.sharkpay.risk.service.GetEvaluation;
import com.sharkpay.risk.service.OpenCase;
import com.sharkpay.risk.service.TransitionCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal risk API (consumed by payments/payouts orchestration and the ops
 * console; contract: contracts/openapi/v1/internal-risk.yaml).
 *
 * <p>Idempotency: {@code POST /evaluations} is keyed by the body's
 * {@code evaluation_id} — a replay returns the original decision, a key
 * reuse with a different payload is 409.</p>
 */
@RestController
@RequestMapping("/internal/v1/risk")
public class RiskApiController {

    private final EvaluateTransaction evaluateTransaction;
    private final GetEvaluation getEvaluation;
    private final OpenCase openCase;
    private final GetCase getCase;
    private final TransitionCase transitionCase;

    public RiskApiController(EvaluateTransaction evaluateTransaction,
                             GetEvaluation getEvaluation,
                             OpenCase openCase,
                             GetCase getCase,
                             TransitionCase transitionCase) {
        this.evaluateTransaction = evaluateTransaction;
        this.getEvaluation = getEvaluation;
        this.openCase = openCase;
        this.getCase = getCase;
        this.transitionCase = transitionCase;
    }

    /**
     * Evaluates a transaction against the active rule set. 200 with the
     * decision and the ordered rule reasons (never 422 — a deny is a
     * successful evaluation).
     */
    @PostMapping("/evaluations")
    public ResponseEntity<EvaluationResponseDto> evaluate(@Valid @RequestBody EvaluationRequestDto body) {
        Evaluation evaluation = evaluateTransaction.evaluate(body.toDomain());
        return ResponseEntity.ok(EvaluationResponseDto.from(evaluation));
    }

    @GetMapping("/evaluations/{id}")
    public EvaluationResponseDto getEvaluation(@PathVariable("id") String id) {
        return EvaluationResponseDto.from(getEvaluation.get(id.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    /** Opens a compliance case (201). */
    @PostMapping("/cases")
    public ResponseEntity<CaseResponseDto> openCase(@Valid @RequestBody CreateCaseRequestDto body) {
        com.sharkpay.risk.domain.Case c = openCase.open(body.subjectPrincipalId(), body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(CaseResponseDto.from(c));
    }

    /** Fetches a case by public id ({@code case_<hex>}) or bare UUID. */
    @GetMapping("/cases/{id}")
    public CaseResponseDto getCase(@PathVariable("id") String id) {
        return CaseResponseDto.from(getCase.get(id));
    }

    /**
     * Transitions a case (legal edges only). 200 with the updated case, 409
     * on an illegal or terminal transition.
     */
    @PostMapping("/cases/{id}/transitions")
    public CaseResponseDto transition(@PathVariable("id") String id, @Valid @RequestBody TransitionRequestDto body) {
        CaseStatus target = com.sharkpay.risk.domain.WireValue.parse(CaseStatus.class, body.status(), "status");
        CaseResolution resolution = (body.resolution() == null || body.resolution().isBlank())
                ? null
                : com.sharkpay.risk.domain.WireValue.parse(CaseResolution.class, body.resolution(), "resolution");
        return CaseResponseDto.from(transitionCase.transition(id, target, body.actor(), resolution));
    }
}
