package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.SandboxPaymentCreateRequest;
import com.sharkpay.gateway.api.dto.SandboxPaymentJson;
import com.sharkpay.gateway.service.Ids;
import com.sharkpay.gateway.service.SandboxPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sandbox simulated provider — clearly separated from the /v1 surface
 * (own {@code /sandbox/*} route class, own scope checks, in-memory state,
 * deterministic scripted accept/settle flow). Each poll advances the
 * payment one step and dispatches the matching webhook event, so merchants
 * can exercise their signed receivers end to end with zero money movement.
 */
@RestController
public final class SandboxController {

    private final SandboxPaymentService sandbox;

    public SandboxController(SandboxPaymentService sandbox) {
        this.sandbox = sandbox;
    }

    /** Creates a scripted payment (CREATED, payment.created dispatched). */
    @PostMapping("/sandbox/payments")
    public ResponseEntity<SandboxPaymentJson> create(@Valid @RequestBody
                                                     SandboxPaymentCreateRequest body) {
        SandboxPaymentService.SandboxPayment payment = sandbox.create(body.amount_minor(),
                body.currency(), body.destination_wallet(), body.rail());
        return ResponseEntity.status(201)
                .header("X-Request-Id", Ids.requestId())
                .body(SandboxPaymentJson.of(payment));
    }

    /** Polls the payment: advances the script exactly one step per call. */
    @GetMapping("/sandbox/payments/{id}")
    public ResponseEntity<SandboxPaymentJson> get(@PathVariable("id") String id) {
        return ResponseEntity.ok()
                .header("X-Request-Id", Ids.requestId())
                .body(SandboxPaymentJson.of(sandbox.get(id)));
    }
}
