package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.ports.IdempotencyCache;
import com.sharkpay.gateway.ports.UpstreamPort;
import com.sharkpay.gateway.ports.UpstreamPort.UpstreamRequest;
import com.sharkpay.gateway.ports.UpstreamPort.UpstreamResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The /v1 passthrough (skeleton until the real upstream HTTP adapter lands
 * at integration, ADR 003 §3): forwards authenticated requests to the
 * internal service that owns the route, with gateway-level idempotency —
 * POSTs carrying an {@code Idempotency-Key} are cached per
 * (scope = key + route class) and replays return the stored response with
 * {@code X-Idempotent-Replay: true}; the same key with a different payload
 * is a 409 {@code idempotency_conflict}.
 *
 * <p>Only responses below 500 are cached — a 5xx is explicitly "safe to
 * retry with the same Idempotency-Key" (common.yaml) so the retry must
 * reach the upstream again.</p>
 */
public final class PassthroughService {

    private final UpstreamPort upstream;
    private final IdempotencyCache idempotency;

    public PassthroughService(UpstreamPort upstream, IdempotencyCache idempotency) {
        this.upstream = Objects.requireNonNull(upstream, "upstream is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency is required");
    }

    /**
     * Forwards one request.
     *
     * @param routeScope      the cache scope for this route class ({@code PASSTHROUGH:<CLASS>})
     * @param method          HTTP method
     * @param path            request path (and query) as received
     * @param body            request body (nullable for GETs)
     * @param idempotencyKey  raw Idempotency-Key header (null when absent — no caching)
     * @param principalId     authenticated caller, propagated upstream
     */
    public Result forward(String routeScope, String method, String path, String body,
                          String idempotencyKey, UUID principalId) {
        Objects.requireNonNull(routeScope, "routeScope is required");
        Objects.requireNonNull(method, "method is required");
        Objects.requireNonNull(path, "path is required");
        Objects.requireNonNull(principalId, "principalId is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return relay(routeScope, method, path, body, principalId, null);
        }
        String key = idempotencyKey.trim();
        String fingerprint = fingerprint(method, path, body);
        Optional<IdempotencyCache.CachedResponse> stored = idempotency.find(routeScope, key);
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(key);
            }
            return new Result(stored.get().status(), stored.get().body(), true);
        }
        return relay(routeScope, method, path, body, principalId, new Cached(key, fingerprint));
    }

    private Result relay(String scope, String method, String path, String body,
                         UUID principalId, Cached pending) {
        UpstreamResponse response = upstream.forward(new UpstreamRequest(method, path, body,
                principalId));
        if (pending != null && response.status() < 500) {
            idempotency.put(scope, pending.key(),
                    IdempotencyCache.CachedResponse.upstream(pending.fingerprint(),
                            response.status(), response.body()));
        }
        return new Result(response.status(), response.body(), false);
    }

    private static String fingerprint(String method, String path, String body) {
        return method + "|" + path + "|" + (body == null ? "" : body);
    }

    private record Cached(String key, String fingerprint) {
    }

    /** @param status  upstream status relayed verbatim
     *  @param body    upstream body relayed verbatim
     *  @param replay  true when served from the idempotency cache */
    public record Result(int status, String body, boolean replay) {
    }
}
