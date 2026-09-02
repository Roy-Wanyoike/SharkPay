package com.sharkpay.payouts.api;

import com.sharkpay.payouts.api.dto.TransferCreateRequest;
import com.sharkpay.payouts.api.dto.TransferJson;
import com.sharkpay.payouts.service.CreateTransferUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public adapter implementing contracts/openapi/v1/transfers.yaml exactly:
 * one endpoint — {@code POST /transfers} (createTransfer). V1 execution is
 * synchronous: the 201 response carries the terminal state (SUCCEEDED, or
 * FAILED for a pre-flight/ledger rejection that never partially posted);
 * the ledger journal entry id is returned as {@code entry_id}.
 * Idempotency-Key required; replays carry X-Idempotent-Replay.
 */
@RestController
public final class TransferController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final CreateTransferUseCase createTransfer;

    public TransferController(CreateTransferUseCase createTransfer) {
        this.createTransfer = createTransfer;
    }

    /** createTransfer (201; replay 201 + X-Idempotent-Replay: true). */
    @PostMapping("/transfers")
    public ResponseEntity<TransferJson> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody TransferCreateRequest request) {
        CreateTransferUseCase.Result result = createTransfer.create(idempotencyKey,
                request.source_wallet(), request.destination_wallet(), request.amount_minor(),
                request.currency(), request.metadata());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201);
        if (result.replay()) {
            response.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(TransferJson.of(result.transfer()));
    }
}
