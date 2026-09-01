package com.sharkpay.payments.api;

import com.sharkpay.payments.api.dto.PaymentJson;
import com.sharkpay.payments.api.dto.ProviderResultRequest;
import com.sharkpay.payments.api.dto.ReverseRequest;
import com.sharkpay.payments.domain.StateTransition;
import com.sharkpay.payments.service.ListPaymentsUseCase;
import com.sharkpay.payments.service.RecordProviderResultUseCase;
import com.sharkpay.payments.service.ReversePaymentUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal (service-to-service) lifecycle adapter: the endpoints the real
 * NATS {@code providers.transfer.*} consumer and ops tooling call once the
 * event binding lands (ADR 003 §3 — fail-fast placeholders until then):
 *
 * <ul>
 *   <li>{@code POST /internal/payments/{id}/provider-result} — apply a
 *       provider transfer status (confirm → capture / compensation);</li>
 *   <li>{@code POST /internal/payments/{id}/reverse} — SUCCEEDED/FAILED →
 *       REVERSED with the ledger compensation pair;</li>
 *   <li>{@code GET /internal/payments/{id}/transitions} — the append-only
 *       transition timeline (§7.3 replayability).</li>
 * </ul>
 */
@RestController
public final class InternalLifecycleController {

    private final RecordProviderResultUseCase recordResult;
    private final ReversePaymentUseCase reverse;
    private final ListPaymentsUseCase listPayments;

    public InternalLifecycleController(RecordProviderResultUseCase recordResult,
                                       ReversePaymentUseCase reverse,
                                       ListPaymentsUseCase listPayments) {
        this.recordResult = recordResult;
        this.reverse = reverse;
        this.listPayments = listPayments;
    }

    /** Applies a provider transfer status to the intent's lifecycle. */
    @PostMapping("/internal/payments/{id}/provider-result")
    public ResponseEntity<PaymentJson> providerResult(
            @RequestHeader(value = PaymentController.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody ProviderResultRequest request) {
        RecordProviderResultUseCase.Result result = recordResult.record(idempotencyKey, id,
                request.status());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replay()) {
            response.header(PaymentController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(PaymentJson.of(result.intent()));
    }

    /** Reverses a succeeded/failed payment (ledger compensation pair). */
    @PostMapping("/internal/payments/{id}/reverse")
    public ResponseEntity<PaymentJson> reverse(
            @RequestHeader(value = PaymentController.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @PathVariable("id") String id,
            @Valid @RequestBody ReverseRequest request) {
        ReversePaymentUseCase.Result result = reverse.reverse(idempotencyKey, id,
                request.amount_minor(), request.reason());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replay()) {
            response.header(PaymentController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(PaymentJson.of(result.intent()));
    }

    /** The intent's full transition timeline (support / recon replay). */
    @GetMapping("/internal/payments/{id}/transitions")
    public List<StateTransition> transitions(@PathVariable("id") String id) {
        return listPayments.timeline(id);
    }
}
