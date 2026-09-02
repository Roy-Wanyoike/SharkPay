package com.sharkpay.reconciliation.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /internal/recon/breaks/{id}/transitions}: a manual
 * lifecycle transition. {@code to} is the target wire name (investigating,
 * resolved, waived — compensated is only reachable via compensation
 * execution).
 */
public record TransitionBreakRequest(
        @NotBlank(message = "to is required") String to,
        @NotBlank(message = "principal is required") @Size(max = 128) String principal,
        @NotBlank(message = "note is required (RB-7: the hypothesis is written in the ticket)")
        @Size(max = 500) String note) {
}
