package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.api.dto.ApproveCompensationRequest;
import com.sharkpay.reconciliation.api.dto.CompensationJson;
import com.sharkpay.reconciliation.service.ApproveAndExecuteCompensationUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The 4-eyes execution surface (RB-7 steps 3–5):
 *
 * <pre>
 *   POST /internal/recon/compensations/{id}/approve   operator B approves + the entry executes
 * </pre>
 *
 * <p>Rejected before any money moves when the approver equals the
 * requester (422 four_eyes_violation), when the compensation already
 * executed (409), or when the break is terminal (409). On success the
 * ledger journal entry id is recorded on the entry and the break, the
 * break becomes COMPENSATED, and the
 * {@code recon.compensation.executed.v1} event is published.</p>
 */
@RestController
public final class CompensationsController {

    private final ApproveAndExecuteCompensationUseCase approveAndExecute;

    public CompensationsController(ApproveAndExecuteCompensationUseCase approveAndExecute) {
        this.approveAndExecute = approveAndExecute;
    }

    /** Approves and executes the compensation (200 with the executed entry). */
    @PostMapping("/internal/recon/compensations/{id}/approve")
    public ResponseEntity<CompensationJson> approve(@PathVariable("id") String id,
                                                    @Valid @RequestBody ApproveCompensationRequest request) {
        ApproveAndExecuteCompensationUseCase.Result result =
                approveAndExecute.approveAndExecute(id, request.approver());
        return ResponseEntity.ok(CompensationJson.of(result.entry()));
    }
}
