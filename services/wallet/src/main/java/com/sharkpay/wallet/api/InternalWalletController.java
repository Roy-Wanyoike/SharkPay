package com.sharkpay.wallet.api;

import com.sharkpay.wallet.api.dto.ChangeStatusRequest;
import com.sharkpay.wallet.api.dto.CreateWalletRequest;
import com.sharkpay.wallet.api.dto.HoldJson;
import com.sharkpay.wallet.api.dto.PlaceHoldRequest;
import com.sharkpay.wallet.api.dto.WalletJson;
import com.sharkpay.wallet.service.ChangeWalletStatusUseCase;
import com.sharkpay.wallet.service.CreateWalletUseCase;
import com.sharkpay.wallet.service.GetWalletUseCase;
import com.sharkpay.wallet.service.PlaceHoldUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal (service-to-service) wallet adapter: create wallet, freeze /
 * unfreeze with audit reason, and place holds. Money-mutating operations
 * require an Idempotency-Key: a replay with the same payload returns the
 * original response (X-Idempotent-Replay: true, no second effect); a replay
 * with a different payload is a 409 idempotency_conflict.
 */
@RestController
public final class InternalWalletController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final CreateWalletUseCase createWallet;
    private final ChangeWalletStatusUseCase changeStatus;
    private final PlaceHoldUseCase placeHold;
    private final GetWalletUseCase getWallet;

    public InternalWalletController(CreateWalletUseCase createWallet,
                                     ChangeWalletStatusUseCase changeStatus,
                                     PlaceHoldUseCase placeHold, GetWalletUseCase getWallet) {
        this.createWallet = createWallet;
        this.changeStatus = changeStatus;
        this.placeHold = placeHold;
        this.getWallet = getWallet;
    }

    /** Creates a wallet (201; replay 201 + X-Idempotent-Replay: true). */
    @PostMapping("/internal/wallets")
    public ResponseEntity<WalletJson> create(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateWalletRequest request) {
        CreateWalletUseCase.Result result = createWallet.create(idempotencyKey,
                request.principal_id(), request.currency());
        return respond(result.replay(), WalletJson.of(result.wallet(),
                getWallet.get(result.wallet().id()).balances()));
    }

    /** Freezes an ACTIVE wallet (audit reason required). */
    @PostMapping("/internal/wallets/{id}/freeze")
    public WalletJson freeze(@PathVariable("id") String id,
                             @Valid @RequestBody ChangeStatusRequest request) {
        return WalletJson.of(changeStatus.freeze(id, request.reason()),
                getWallet.get(id).balances());
    }

    /** Unfreezes a FROZEN wallet (audit reason required). */
    @PostMapping("/internal/wallets/{id}/unfreeze")
    public WalletJson unfreeze(@PathVariable("id") String id,
                               @Valid @RequestBody ChangeStatusRequest request) {
        return WalletJson.of(changeStatus.unfreeze(id, request.reason()),
                getWallet.get(id).balances());
    }

    /** Places a hold on an ACTIVE wallet (201; replay 201 + replay header). */
    @PostMapping("/internal/wallets/{id}/holds")
    public ResponseEntity<HoldJson> placeHold(
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @PathVariable("id") String walletId,
            @Valid @RequestBody PlaceHoldRequest request) {
        PlaceHoldUseCase.Result result = placeHold.place(idempotencyKey, walletId,
                request.amount_minor(), request.currency(), request.source(),
                request.source_ref(), request.reason());
        return respond(result.replay(), HoldJson.of(result.hold()));
    }

    static <T> ResponseEntity<T> respond(boolean replay, T body) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (replay) {
            response.header(IDEMPOTENT_REPLAY_HEADER, "true");
        }
        return response.body(body);
    }
}
