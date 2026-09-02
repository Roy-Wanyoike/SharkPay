package com.sharkpay.gateway.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * POST /v1/api-keys request body. The principal is taken from the
 * authenticated caller, never the body; scopes are validated against the
 * fail-closed catalog.
 */
public record ApiKeyCreateRequest(
        @NotNull @NotEmpty List<String> scopes,
        Integer rpm_limit,
        Long monthly_limit) {
}
