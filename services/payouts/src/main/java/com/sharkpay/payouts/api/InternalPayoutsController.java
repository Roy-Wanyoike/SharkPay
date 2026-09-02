package com.sharkpay.payouts.api;

import com.sharkpay.payouts.api.dto.InternalReportJson;
import com.sharkpay.payouts.api.dto.PayoutJson;
import com.sharkpay.payouts.api.dto.ProviderResultRequest;
import com.sharkpay.payouts.api.dto.RiskDecisionRequest;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.service.HandleProviderResultUseCase;
import com.sharkpay.payouts.service.HandleRiskDecisionUseCase;
import com.sharkpay.payouts.service.PayoutSweeper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * Internal (service-to-service) adapter: provider result ingestion (the
 * providers service's verified callbacks and poll outcomes), risk-decision
 * intake (the risk service's verdicts feeding BLOCKED) and the scheduler
 * tick trigger (ops + integration testing). All money-mutating ingestion is
 * idempotent — a replayed provider return reference applies nothing new.
 */
@RestController
public final class InternalPayoutsController {

    private final HandleProviderResultUseCase providerResults;
    private final HandleRiskDecisionUseCase riskDecisions;
    private final PayoutSweeper sweeper;

    public InternalPayoutsController(HandleProviderResultUseCase providerResults,
                                     HandleRiskDecisionUseCase riskDecisions,
                                     PayoutSweeper sweeper) {
        this.providerResults = providerResults;
        this.riskDecisions = riskDecisions;
        this.sweeper = sweeper;
    }

    /**
     * Ingests one provider result for a payout (200; a replayed return
     * reference re-returns the already-applied state with
     * X-Idempotent-Replay).
     */
    @PostMapping("/internal/payouts/{id}/provider-result")
    public ResponseEntity<PayoutJson> providerResult(@PathVariable("id") String payoutId,
                                                     @Valid @RequestBody ProviderResultRequest body) {
        ProviderGatewayPort.ProviderStatus status = ProviderGatewayPort.ProviderStatus.valueOf(
                body.status().trim().toUpperCase(Locale.ROOT));
        HandleProviderResultUseCase.Result result = providerResults.ingest(payoutId, status,
                body.provider_ref(), body.reason(), body.returned_amount_minor(),
                body.returned_currency(), body.provider_return_ref());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replay()) {
            response.header(TransferController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(PayoutJson.of(result.payout()));
    }

    /** Applies the risk service's verdict (200 with the payout state). */
    @PostMapping("/internal/payouts/{id}/risk-decision")
    public PayoutJson riskDecision(@PathVariable("id") String payoutId,
                                   @Valid @RequestBody RiskDecisionRequest body) {
        return PayoutJson.of(riskDecisions.apply(payoutId, body.decision(), body.reason()));
    }

    /** Runs one scheduler tick (release batch + TTL sweep + poll batch). */
    @PostMapping("/internal/payouts/scheduler/tick")
    public InternalReportJson tick() {
        PayoutSweeper.TickReport report = sweeper.runTick();
        return new InternalReportJson(
                report.release().considered(),
                report.release().submitted(),
                report.release().retried(),
                report.release().failedTerminal(),
                report.expiry().cancelled(),
                report.poll().evaluated());
    }
}
