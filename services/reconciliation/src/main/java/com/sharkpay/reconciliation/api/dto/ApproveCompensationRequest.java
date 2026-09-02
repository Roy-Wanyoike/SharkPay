package com.sharkpay.reconciliation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/recon/compensations/{id}/approve}:
 * operator B's approval — the second pair of eyes. The approver must
 * differ from the requester (4-eyes, SECURITY §4 / RB-7 step 3).
 */
public record ApproveCompensationRequest(
        @NotBlank(message = "approver is required") @Size(max = 128) String approver) {
}
