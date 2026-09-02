package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.api.dto.ReconRunJson;
import com.sharkpay.reconciliation.api.dto.SettlementReportJson;
import com.sharkpay.reconciliation.api.dto.TriggerRunRequest;
import com.sharkpay.reconciliation.ports.ReconRunRepository;
import com.sharkpay.reconciliation.service.BreakView;
import com.sharkpay.reconciliation.service.GetReconRunUseCase;
import com.sharkpay.reconciliation.service.GetSettlementReportUseCase;
import com.sharkpay.reconciliation.service.TriggerReconRunUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * Internal (ops/finance) recon-run surface:
 *
 * <pre>
 *   POST /internal/recon/runs                       trigger a run (Idempotency-Key)
 *   GET  /internal/recon/runs?provider=             runs of a provider (newest first)
 *   GET  /internal/recon/runs/{id}                  run + breaks (live aging)
 *   GET  /internal/recon/runs/{id}/settlement-report
 * </pre>
 *
 * <p>The trigger is idempotent on the Idempotency-Key: a replay with the
 * same payload returns the original run ({@code X-Idempotent-Replay: true},
 * no second effect, no second events); a different payload is a 409
 * conflict. An unavailable statement side is not a transport error: the run
 * is returned FAILED with the reason.</p>
 */
@RestController
public final class ReconRunsController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final TriggerReconRunUseCase triggerRun;
    private final GetReconRunUseCase getRun;
    private final GetSettlementReportUseCase settlementReports;
    private final ReconRunRepository runs;
    private final Clock clock;

    public ReconRunsController(TriggerReconRunUseCase triggerRun, GetReconRunUseCase getRun,
                               GetSettlementReportUseCase settlementReports,
                               ReconRunRepository runs, Clock clock) {
        this.triggerRun = triggerRun;
        this.getRun = getRun;
        this.settlementReports = settlementReports;
        this.runs = runs;
        this.clock = clock;
    }

    /** Triggers a run (201; replay 201 + X-Idempotent-Replay: true). */
    @PostMapping("/internal/recon/runs")
    public ResponseEntity<ReconRunJson> trigger(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TriggerRunRequest request) {
        TriggerReconRunUseCase.Result result = triggerRun.trigger(idempotencyKey, request.provider(),
                request.from(), request.to());
        List<BreakView> breakViews = result.breaks().stream()
                .map(break_ -> BreakView.of(break_, clock))
                .toList();
        return respond(result.replay(), ReconRunJson.of(result.run(), breakViews));
    }

    /** Runs of one provider, newest first (summaries, no break bodies). */
    @GetMapping("/internal/recon/runs")
    public List<ReconRunJson> list(@RequestParam(value = "provider", required = false) String provider) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider query parameter is required");
        }
        return runs.listByProvider(provider.trim()).stream()
                .map(ReconRunJson::summary)
                .toList();
    }

    /** The run with its breaks, each carrying live aging. */
    @GetMapping("/internal/recon/runs/{id}")
    public ReconRunJson get(@PathVariable("id") String id) {
        GetReconRunUseCase.Result result = getRun.get(id);
        return ReconRunJson.of(result.run(), result.breaks());
    }

    /** The run's settlement report (404 when the run FAILED). */
    @GetMapping("/internal/recon/runs/{id}/settlement-report")
    public SettlementReportJson settlementReport(@PathVariable("id") String id) {
        return SettlementReportJson.of(settlementReports.byRun(id));
    }

    static <T> ResponseEntity<T> respond(boolean replay, T body) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (replay) {
            response.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(body);
    }
}
