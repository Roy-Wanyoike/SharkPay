package com.sharkpay.payments.api;

import com.sharkpay.payments.api.dto.PaymentCreateRequest;
import com.sharkpay.payments.api.dto.PaymentJson;
import com.sharkpay.payments.api.dto.PaymentListJson;
import com.sharkpay.payments.ports.PaymentRepository.Page;
import com.sharkpay.payments.ports.PrincipalResolver;
import com.sharkpay.payments.ports.Randomness;
import com.sharkpay.payments.service.CancelPaymentUseCase;
import com.sharkpay.payments.service.CreatePaymentUseCase;
import com.sharkpay.payments.service.GetPaymentUseCase;
import com.sharkpay.payments.service.ListPaymentsUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Public REST adapter implementing contracts/openapi/v1/payments.yaml
 * EXACTLY: createPayment (POST /payments, 201 + X-Idempotent-Replay),
 * listPayments (GET /payments, cursor pagination + filters), getPayment
 * (GET /payments/{id}), cancelPayment (POST /payments/{id}/cancel, 200 +
 * replay header, 409 state_conflict on confirmed/terminal intents).
 *
 * <p>Common error semantics follow the wallet service: 400 validation_error,
 * 404 not_found, 409 idempotency_conflict / state_conflict, 422 business
 * rejections (unsupported_currency, risk_blocked, money_overflow, ...) —
 * see {@link GlobalExceptionHandler}.</p>
 */
@RestController
public final class PaymentController {

    /** Idempotency header (common.yaml IdempotencyKey parameter). */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** Replay marker (common.yaml IdempotentReplay header). */
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";
    /** Server-assigned request id (common.yaml RequestId header). */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final CreatePaymentUseCase createPayment;
    private final CancelPaymentUseCase cancelPayment;
    private final GetPaymentUseCase getPayment;
    private final ListPaymentsUseCase listPayments;
    private final PrincipalResolver principals;
    private final Randomness randomness;

    public PaymentController(CreatePaymentUseCase createPayment,
                             CancelPaymentUseCase cancelPayment, GetPaymentUseCase getPayment,
                             ListPaymentsUseCase listPayments, PrincipalResolver principals,
                             Randomness randomness) {
        this.createPayment = createPayment;
        this.cancelPayment = cancelPayment;
        this.getPayment = getPayment;
        this.listPayments = listPayments;
        this.principals = principals;
        this.randomness = randomness;
    }

    /** createPayment: synchronous risk → hold → provider hand-off. */
    @PostMapping("/payments")
    public ResponseEntity<PaymentJson> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody PaymentCreateRequest request) {
        CreatePaymentUseCase.Result result = createPayment.create(idempotencyKey,
                principals.resolve(), request.amount_minor(), request.currency(),
                request.destination_wallet(), request.rail(), request.metadata(),
                request.expires_in_seconds());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(201);
        headers(response, result.replay());
        return response.body(PaymentJson.of(result.intent()));
    }

    /** listPayments: filter by state / principal / created range, cursor-paged. */
    @GetMapping("/payments")
    public PaymentListJson list(@RequestParam(required = false) String state,
                                @RequestParam(required = false) UUID principal_id,
                                @RequestParam(required = false) Instant created_from,
                                @RequestParam(required = false) Instant created_to,
                                @RequestParam(required = false) Integer limit,
                                @RequestParam(required = false) String cursor) {
        Page page = listPayments.list(state, principal_id, created_from, created_to, limit,
                cursor);
        return PaymentListJson.of(page.items(), page.nextCursor());
    }

    /** getPayment: poll the intent's current state. */
    @GetMapping("/payments/{id}")
    public PaymentJson get(@PathVariable("id") String id) {
        return PaymentJson.of(getPayment.get(id));
    }

    /** cancelPayment: CREATED/PENDING_PROVIDER → CANCELLED, hold released. */
    @PostMapping("/payments/{id}/cancel")
    public ResponseEntity<PaymentJson> cancel(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable("id") String id) {
        CancelPaymentUseCase.Result result = cancelPayment.cancel(idempotencyKey, id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        headers(response, result.replay());
        return response.body(PaymentJson.of(result.intent()));
    }

    private void headers(ResponseEntity.BodyBuilder response, boolean replay) {
        response.header(REQUEST_ID_HEADER, randomness.requestId());
        if (replay) {
            response.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
    }
}
