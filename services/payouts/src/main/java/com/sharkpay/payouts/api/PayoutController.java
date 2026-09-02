package com.sharkpay.payouts.api;

import com.sharkpay.payouts.api.dto.PayoutCreateRequest;
import com.sharkpay.payouts.api.dto.PayoutJson;
import com.sharkpay.payouts.service.CancelPayoutUseCase;
import com.sharkpay.payouts.service.CreatePayoutUseCase;
import com.sharkpay.payouts.service.GetPayoutUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public adapter implementing contracts/openapi/v1/payouts.yaml exactly:
 * create payout (201, terminal-or-pending state, X-Idempotent-Replay on
 * replays), get payout status (200/404) and cancel before provider
 * acceptance (200/409). {@code SENT} means accepted by the rail;
 * {@code SUCCEEDED} means settled at the destination.
 */
@RestController
public final class PayoutController {

    private final CreatePayoutUseCase createPayout;
    private final GetPayoutUseCase getPayout;
    private final CancelPayoutUseCase cancelPayout;

    public PayoutController(CreatePayoutUseCase createPayout, GetPayoutUseCase getPayout,
                            CancelPayoutUseCase cancelPayout) {
        this.createPayout = createPayout;
        this.getPayout = getPayout;
        this.cancelPayout = cancelPayout;
    }

    /** createPayout (201; replay 201 + X-Idempotent-Replay: true). */
    @PostMapping("/payouts")
    public ResponseEntity<PayoutJson> create(
            @RequestHeader(value = TransferController.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody PayoutCreateRequest request) {
        CreatePayoutUseCase.Result result = createPayout.create(idempotencyKey,
                request.source_wallet(), request.amount_minor(), request.currency(),
                request.destination().toDomain(), request.rail(), request.metadata(),
                request.expires_in_seconds(), null);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201);
        if (result.replay()) {
            response.header(TransferController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(PayoutJson.of(result.payout()));
    }

    /** getPayout (200; 404 when unknown). */
    @GetMapping("/payouts/{id}")
    public PayoutJson get(@PathVariable("id") String id) {
        return PayoutJson.of(getPayout.get(id));
    }

    /** cancelPayout (200; 409 state_conflict once past PENDING_RISK). */
    @PostMapping("/payouts/{id}/cancel")
    public ResponseEntity<PayoutJson> cancel(
            @RequestHeader(value = TransferController.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @PathVariable("id") String payoutId) {
        CancelPayoutUseCase.Result result = cancelPayout.cancel(idempotencyKey, payoutId, null);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replay()) {
            response.header(TransferController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(PayoutJson.of(result.payout()));
    }
}
