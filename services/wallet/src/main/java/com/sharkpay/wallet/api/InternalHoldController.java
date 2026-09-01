package com.sharkpay.wallet.api;

import com.sharkpay.wallet.api.dto.CaptureRequest;
import com.sharkpay.wallet.api.dto.HoldJson;
import com.sharkpay.wallet.api.dto.ReleaseRequest;
import com.sharkpay.wallet.ports.HoldRepository;
import com.sharkpay.wallet.service.CaptureHoldUseCase;
import com.sharkpay.wallet.service.ReleaseHoldUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Internal (service-to-service) hold adapter: read a hold, release it, and
 * capture it (full or partial). Release and capture require an
 * Idempotency-Key.
 */
@RestController
public final class InternalHoldController {

    private final ReleaseHoldUseCase releaseHold;
    private final CaptureHoldUseCase captureHold;
    private final HoldRepository holds;

    public InternalHoldController(ReleaseHoldUseCase releaseHold, CaptureHoldUseCase captureHold,
                                  HoldRepository holds) {
        this.releaseHold = releaseHold;
        this.captureHold = captureHold;
        this.holds = holds;
    }

    /** Reads a hold by id (404 when unknown). */
    @GetMapping("/internal/holds/{id}")
    public HoldJson get(@PathVariable("id") String id) {
        return HoldJson.of(holds.findById(id == null ? "" : id.trim())
                .orElseThrow(() -> new NoSuchElementException("hold " + id + " not found")));
    }

    /** Releases a hold (200; replay 200 + X-Idempotent-Replay: true). */
    @PostMapping("/internal/holds/{id}/release")
    public ResponseEntity<HoldJson> release(
            @RequestHeader(value = InternalWalletController.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable("id") String holdId,
            @Valid @RequestBody(required = false) ReleaseRequest request) {
        ReleaseHoldUseCase.Result result = releaseHold.release(idempotencyKey, holdId,
                request == null ? null : request.reason());
        return replayAware(result.replay(), HoldJson.of(result.hold()));
    }

    /**
     * Captures a hold (200; replay 200 + X-Idempotent-Replay: true).
     * Partial capture: {@code amount_minor} below the reserved amount — the
     * remainder is released.
     */
    @PostMapping("/internal/holds/{id}/capture")
    public ResponseEntity<HoldJson> capture(
            @RequestHeader(value = InternalWalletController.IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable("id") String holdId,
            @Valid @RequestBody(required = false) CaptureRequest request) {
        CaptureHoldUseCase.Result result = captureHold.capture(idempotencyKey, holdId,
                request == null ? null : request.amount_minor(),
                request == null ? null : request.reason());
        return replayAware(result.replay(), HoldJson.of(result.hold()));
    }

    private static <T> ResponseEntity<T> replayAware(boolean replay, T body) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (replay) {
            response.header(InternalWalletController.IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(body);
    }
}
