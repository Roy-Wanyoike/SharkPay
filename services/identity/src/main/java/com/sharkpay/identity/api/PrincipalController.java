package com.sharkpay.identity.api;

import com.sharkpay.identity.api.dto.CreateAgentRequest;
import com.sharkpay.identity.api.dto.CreatePrincipalRequest;
import com.sharkpay.identity.api.dto.EnumParser;
import com.sharkpay.identity.api.dto.KycDecisionRequest;
import com.sharkpay.identity.api.dto.ChangeStatusRequest;
import com.sharkpay.identity.api.dto.PrincipalResponse;
import com.sharkpay.identity.api.dto.VerifyKycResponse;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.service.ChangePrincipalStatusUseCase;
import com.sharkpay.identity.service.CreateAgentUseCase;
import com.sharkpay.identity.service.CreatePrincipalUseCase;
import com.sharkpay.identity.service.GetPrincipalUseCase;
import com.sharkpay.identity.service.VerifyKycUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST API of the identity service. Consumed by other SharkPay
 * services; authenticated with Keycloak JWTs in production (tests bypass
 * security entirely).
 */
@RestController
@RequestMapping("/internal/v1")
public class PrincipalController {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

    private final CreatePrincipalUseCase createPrincipal;
    private final CreateAgentUseCase createAgent;
    private final GetPrincipalUseCase getPrincipal;
    private final ChangePrincipalStatusUseCase changePrincipalStatus;
    private final VerifyKycUseCase verifyKyc;

    public PrincipalController(CreatePrincipalUseCase createPrincipal,
                               CreateAgentUseCase createAgent,
                               GetPrincipalUseCase getPrincipal,
                               ChangePrincipalStatusUseCase changePrincipalStatus,
                               VerifyKycUseCase verifyKyc) {
        this.createPrincipal = createPrincipal;
        this.createAgent = createAgent;
        this.getPrincipal = getPrincipal;
        this.changePrincipalStatus = changePrincipalStatus;
        this.verifyKyc = verifyKyc;
    }

    /**
     * Creates a principal. Idempotent when an Idempotency-Key header is
     * supplied: identical replay returns the original principal with 200,
     * the same key with a different body returns 409.
     */
    @PostMapping("/principals")
    public ResponseEntity<PrincipalResponse> createPrincipal(
            @Valid @RequestBody CreatePrincipalRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        CreatePrincipalUseCase.Command command = new CreatePrincipalUseCase.Command(
                EnumParser.parse(PrincipalType.class, request.type(), "INVALID_PRINCIPAL_TYPE"),
                parseOwnerSharkId(request.ownerSharkId()),
                normalizeIdempotencyKey(idempotencyKey));
        return respond(createPrincipal.execute(command));
    }

    @GetMapping("/principals/{id}")
    public PrincipalResponse getPrincipal(@PathVariable("id") String id) {
        return getPrincipal.byId(Uuids.parse(id, "principal id"))
                .map(PrincipalResponse::from)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + id));
    }

    @GetMapping("/sharkids/{sharkId}")
    public PrincipalResponse getPrincipalBySharkId(@PathVariable("sharkId") String sharkId) {
        return getPrincipal.bySharkId(SharkId.of(sharkId))
                .map(PrincipalResponse::from)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with shark id " + sharkId));
    }

    /** Records a KYC decision; APPROVED decisions advance the tier. */
    @PostMapping("/principals/{id}/kyc")
    public VerifyKycResponse verifyKyc(
            @PathVariable("id") String id,
            @Valid @RequestBody KycDecisionRequest request) {
        VerifyKycUseCase.Result result = verifyKyc.execute(
                Uuids.parse(id, "principal id"),
                EnumParser.parse(KycTier.class, request.tier(), "INVALID_KYC_TIER"),
                EnumParser.parse(KycStatus.class, request.status(), "INVALID_KYC_STATUS"),
                normalizeProviderRef(request.providerRef()));
        return VerifyKycResponse.from(result);
    }

    /** Creates an AGENT principal owned by the referenced principal. */
    @PostMapping("/agents")
    public ResponseEntity<PrincipalResponse> createAgent(
            @Valid @RequestBody CreateAgentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return respond(createAgent.execute(
                SharkId.of(request.ownerSharkId().trim()),
                normalizeIdempotencyKey(idempotencyKey)));
    }

    /** Applies a status transition (CLOSED is terminal; suspension resets the tier). */
    @PostMapping("/principals/{id}/status")
    public PrincipalResponse changeStatus(
            @PathVariable("id") String id,
            @Valid @RequestBody ChangeStatusRequest request) {
        return PrincipalResponse.from(changePrincipalStatus.execute(
                Uuids.parse(id, "principal id"),
                EnumParser.parse(PrincipalStatus.class, request.status(), "INVALID_PRINCIPAL_STATUS")));
    }

    private ResponseEntity<PrincipalResponse> respond(CreatePrincipalUseCase.Result result) {
        return switch (result) {
            case CreatePrincipalUseCase.Created(var principal) ->
                    ResponseEntity.status(HttpStatus.CREATED).body(PrincipalResponse.from(principal));
            case CreatePrincipalUseCase.Replayed(var principal) ->
                    ResponseEntity.ok(PrincipalResponse.from(principal));
            case CreatePrincipalUseCase.Conflict(var key) -> throw new ConflictException(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency-Key '" + key + "' was already used with a different request body");
        };
    }

    private static SharkId parseOwnerSharkId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return SharkId.of(raw.trim());
    }

    private static String normalizeIdempotencyKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new com.sharkpay.identity.domain.exception.ValidationException("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String normalizeProviderRef(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
