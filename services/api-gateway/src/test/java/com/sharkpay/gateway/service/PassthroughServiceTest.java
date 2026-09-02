package com.sharkpay.gateway.service;

import com.sharkpay.gateway.domain.IdempotencyConflictException;
import com.sharkpay.gateway.fakes.FakeUpstream;
import com.sharkpay.gateway.fakes.InMemoryIdempotencyCache;
import com.sharkpay.gateway.ports.UpstreamPort.UpstreamResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /v1 passthrough: upstream forwarding with principal propagation and
 * idempotent response caching scoped to (route class, key) — mission item 5.
 */
class PassthroughServiceTest {

    private static final UUID PRINCIPAL = UUID.randomUUID();

    private final FakeUpstream upstream = new FakeUpstream();
    private final InMemoryIdempotencyCache cache = new InMemoryIdempotencyCache();
    private final PassthroughService service = new PassthroughService(upstream, cache);

    @Test
    void forwardsMethodPathBodyAndPrincipalToTheUpstream() {
        upstream.respondWith(request -> new UpstreamResponse(200, "{\"ok\":true}"));

        PassthroughService.Result result = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{\"amount_minor\":1000}", null, PRINCIPAL);

        assertEquals(200, result.status());
        assertEquals("{\"ok\":true}", result.body());
        org.junit.jupiter.api.Assertions.assertFalse(result.replay());
        assertEquals(1, upstream.forwardCount());
        FakeUpstream.Forwarded forwarded = upstream.forwardedRequests().get(0);
        assertEquals("POST", forwarded.method());
        assertEquals("/v1/payments", forwarded.path());
        assertEquals("{\"amount_minor\":1000}", forwarded.body());
        assertEquals(PRINCIPAL, forwarded.principalId());
    }

    @Test
    void replaysReturnTheStoredResponseWithoutTouchingTheUpstream() {
        upstream.respondWith(request -> new UpstreamResponse(201, "{\"id\":\"pay_1\"}"));

        PassthroughService.Result first = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{\"amount_minor\":1000}", "idem-1", PRINCIPAL);
        PassthroughService.Result replay = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{\"amount_minor\":1000}", "idem-1", PRINCIPAL);

        assertEquals(first.status(), replay.status());
        assertEquals(first.body(), replay.body());
        assertEquals(1, upstream.forwardCount()); // the upstream saw it once
        assertEquals(1, cache.all().size());
    }

    @Test
    void theSameKeyWithADifferentPayloadIsAConflict() {
        upstream.respondWith(request -> new UpstreamResponse(201, "{\"id\":\"pay_1\"}"));
        service.forward("PASSTHROUGH:PAYMENTS", "POST", "/v1/payments",
                "{\"amount_minor\":1000}", "idem-1", PRINCIPAL);

        IdempotencyConflictException conflict = assertThrows(IdempotencyConflictException.class,
                () -> service.forward("PASSTHROUGH:PAYMENTS", "POST", "/v1/payments",
                        "{\"amount_minor\":2000}", "idem-1", PRINCIPAL));
        assertEquals("idem-1", conflict.idempotencyKey());
        assertEquals(1, upstream.forwardCount());
    }

    @Test
    void theCacheIsScopedToTheRouteClass() {
        upstream.respondWith(request -> new UpstreamResponse(201, "{\"id\":\"x\"}"));
        // same key, same payload, different route: two separate cache entries
        service.forward("PASSTHROUGH:PAYMENTS", "POST", "/v1/payments", "{}", "idem-1", PRINCIPAL);
        service.forward("PASSTHROUGH:PAYOUTS", "POST", "/v1/payouts", "{}", "idem-1", PRINCIPAL);
        PassthroughService.Result crossRouteReplay = service.forward("PASSTHROUGH:PAYMENTS",
                "POST", "/v1/payments", "{}", "idem-1", PRINCIPAL);

        assertTrue(crossRouteReplay.replay());
        assertEquals(2, upstream.forwardCount());
        assertEquals(2, cache.all().size());
    }

    @Test
    void serverErrorsAreNotCachedSoRetriesReachTheUpstream() {
        upstream.respondWith(request -> new UpstreamResponse(503, "{\"error\":true}"));

        PassthroughService.Result first = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{}", "idem-1", PRINCIPAL);
        assertEquals(503, first.status());
        org.junit.jupiter.api.Assertions.assertFalse(first.replay());
        // the 5xx was NOT cached — a retry with the same key must reach the
        // upstream again (common.yaml: 500 is "safe to retry")
        assertEquals(0, cache.all().size());

        upstream.respondWith(request -> new UpstreamResponse(200, "{\"recovered\":true}"));
        PassthroughService.Result retry = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{}", "idem-1", PRINCIPAL);
        assertEquals(200, retry.status());
        assertEquals(2, upstream.forwardCount());
        // the recovered 200 IS now cached (idempotent from here on)
        assertEquals(1, cache.all().size());
        PassthroughService.Result replay = service.forward("PASSTHROUGH:PAYMENTS", "POST",
                "/v1/payments", "{}", "idem-1", PRINCIPAL);
        assertTrue(replay.replay());
        assertEquals(2, upstream.forwardCount());
    }

    @Test
    void requestsWithoutAnIdempotencyKeyAreRelayedUncached() {
        upstream.respondWith(request -> new UpstreamResponse(200, "{\"ok\":true}"));
        service.forward("PASSTHROUGH:FX", "GET", "/v1/fx/quotes", null, null, PRINCIPAL);
        service.forward("PASSTHROUGH:FX", "GET", "/v1/fx/quotes", null, "", PRINCIPAL);
        PassthroughService.Result third = service.forward("PASSTHROUGH:FX", "GET",
                "/v1/fx/quotes", null, "   ", PRINCIPAL);

        assertEquals(3, upstream.forwardCount());
        assertEquals(0, cache.all().size());
        org.junit.jupiter.api.Assertions.assertFalse(third.replay());
        assertEquals("{\"ok\":true}", third.body());
    }

    @Test
    void aBlankBodyIsPartOfTheFingerprintForGetRelays() {
        upstream.respondWith(request -> new UpstreamResponse(200, "{}"));
        service.forward("PASSTHROUGH:WALLETS", "GET", "/v1/wallets", null, "idem-2", PRINCIPAL);
        // same request: replay
        assertTrue(service.forward("PASSTHROUGH:WALLETS", "GET", "/v1/wallets", null, "idem-2",
                PRINCIPAL).replay());
        // different query string: conflict (different fingerprint)
        assertThrows(IdempotencyConflictException.class,
                () -> service.forward("PASSTHROUGH:WALLETS", "GET", "/v1/wallets?limit=5", null,
                        "idem-2", PRINCIPAL));
    }

    @Test
    void argumentsAreValidated() {
        assertThrows(NullPointerException.class,
                () -> service.forward(null, "GET", "/v1/fx", null, null, PRINCIPAL));
        assertThrows(NullPointerException.class,
                () -> service.forward("S", null, "/v1/fx", null, null, PRINCIPAL));
        assertThrows(NullPointerException.class,
                () -> service.forward("S", "GET", null, null, null, PRINCIPAL));
        assertThrows(NullPointerException.class,
                () -> service.forward("S", "GET", "/v1/fx", null, null, null));
    }
}
