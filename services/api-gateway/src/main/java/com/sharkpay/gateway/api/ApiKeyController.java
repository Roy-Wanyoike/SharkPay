package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.ApiKeyCreateRequest;
import com.sharkpay.gateway.api.dto.ApiKeyJson;
import com.sharkpay.gateway.api.dto.ApiKeyListJson;
import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.IdempotencyCache;
import com.sharkpay.gateway.service.ApiKeyAdminUseCase;
import com.sharkpay.gateway.service.CreateApiKeyUseCase;
import com.sharkpay.gateway.service.Ids;
import com.sharkpay.gateway.service.RotateApiKeyUseCase;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * API key management (the gateway's own surface, guarded by the
 * {@code apikeys:manage} scope — see README for the bootstrap note).
 *
 * <p>Idempotency: create and rotate require an {@code Idempotency-Key}
 * header; the cache stores the created entity id (never a response body —
 * plaintext secrets never reach storage). Replays with the same key +
 * payload return the stored entity redacted (the secret exists exactly
 * once, in the original response) with {@code X-Idempotent-Replay: true};
 * the same key with a different payload is a 409. Revoke is naturally
 * idempotent (204).</p>
 */
@RestController
public final class ApiKeyController {

    static final String CREATE_SCOPE = "CREATE_API_KEY";
    static final String ROTATE_SCOPE = "ROTATE_API_KEY";

    private final CreateApiKeyUseCase create;
    private final RotateApiKeyUseCase rotate;
    private final ApiKeyAdminUseCase admin;
    private final IdempotencyCache idempotency;
    private final ApiKeyRepository keys;

    public ApiKeyController(CreateApiKeyUseCase create, RotateApiKeyUseCase rotate,
                            ApiKeyAdminUseCase admin, IdempotencyCache idempotency,
                            ApiKeyRepository keys) {
        this.create = create;
        this.rotate = rotate;
        this.admin = admin;
        this.idempotency = idempotency;
        this.keys = keys;
    }

    /** Creates a key; the plaintext secret appears in this response only. */
    @PostMapping("/v1/api-keys")
    public ResponseEntity<ApiKeyJson> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ApiKeyCreateRequest body,
            HttpServletRequest request) {
        UUID principal = AuthenticatedRequest.principal(request);
        String key = idempotencyKey.trim();
        String fingerprint = principal + "|" + body;
        Optional<IdempotencyCache.CachedResponse> stored = idempotency.find(CREATE_SCOPE, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            ApiKey original = keys.findById(stored.get().entityId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "api key " + stored.get().entityId() + " referenced by idempotency "
                                    + "key " + key + " is missing"));
            return ResponseEntity.status(201)
                    .header("X-Request-Id", Ids.requestId())
                    .header("X-Idempotent-Replay", "true")
                    .body(ApiKeyJson.redacted(original));
        }
        CreateApiKeyUseCase.Result result = create.create(principal,
                Scope.parseAll(body.scopes()), body.rpm_limit(), body.monthly_limit());
        idempotency.put(CREATE_SCOPE, key,
                IdempotencyCache.CachedResponse.entity(fingerprint, 201, result.key().id()));
        return ResponseEntity.status(201)
                .header("X-Request-Id", Ids.requestId())
                .body(ApiKeyJson.withSecret(result.key(), result.plaintext()));
    }

    /** Rotates: old secret valid 24 h (grace), new secret in this response only. */
    @PostMapping("/v1/api-keys/{id}/rotate")
    public ResponseEntity<ApiKeyJson> rotate(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable("id") String id,
            HttpServletRequest request) {
        UUID principal = AuthenticatedRequest.principal(request);
        String key = idempotencyKey.trim();
        String fingerprint = principal + "|" + id;
        Optional<IdempotencyCache.CachedResponse> stored = idempotency.find(ROTATE_SCOPE, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            ApiKey original = keys.findById(stored.get().entityId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "api key " + stored.get().entityId() + " referenced by idempotency "
                                    + "key " + key + " is missing"));
            return ResponseEntity.status(201)
                    .header("X-Request-Id", Ids.requestId())
                    .header("X-Idempotent-Replay", "true")
                    .body(ApiKeyJson.redacted(original));
        }
        RotateApiKeyUseCase.Result result = rotate.rotate(id, principal);
        idempotency.put(ROTATE_SCOPE, key,
                IdempotencyCache.CachedResponse.entity(fingerprint, 201, result.fresh().id()));
        return ResponseEntity.status(201)
                .header("X-Request-Id", Ids.requestId())
                .body(ApiKeyJson.withSecret(result.fresh(), result.plaintext()));
    }

    /** Revokes the key immediately (idempotent 204). */
    @PostMapping("/v1/api-keys/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable("id") String id, HttpServletRequest request) {
        admin.revoke(id, AuthenticatedRequest.principal(request));
        return ResponseEntity.noContent().header("X-Request-Id", Ids.requestId()).build();
    }

    /** Lists the caller's keys (never any secret material). */
    @GetMapping("/v1/api-keys")
    public ApiKeyListJson list(HttpServletRequest request,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) String cursor) {
        int pageSize = sanitizedLimit(limit);
        List<ApiKey> page = admin.list(AuthenticatedRequest.principal(request), pageSize, cursor);
        String nextCursor = page.size() == pageSize ? page.get(page.size() - 1).id() : null;
        return new ApiKeyListJson(page.stream().map(ApiKeyJson::redacted).toList(), nextCursor);
    }

    private static int sanitizedLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return limit;
    }
}
