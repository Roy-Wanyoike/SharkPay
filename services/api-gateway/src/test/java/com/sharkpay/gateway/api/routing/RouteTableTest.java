package com.sharkpay.gateway.api.routing;

import com.sharkpay.gateway.domain.Scope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The route table — the single place the gateway decides which surface a
 * path belongs to — and the route class → required-scope matrix. Unknown
 * paths resolve to UNKNOWN (fail-closed 403 upstream of any controller).
 */
class RouteTableTest {

    @Test
    void everyPublicRouteResolvesToItsClass() {
        assertEquals(RouteClass.PAYMENTS, RouteTable.resolve("/v1/payments"));
        assertEquals(RouteClass.PAYMENTS, RouteTable.resolve("/v1/payments/pay_1"));
        assertEquals(RouteClass.PAYMENTS, RouteTable.resolve("/v1/payments/pay_1/cancel"));
        assertEquals(RouteClass.PAYOUTS, RouteTable.resolve("/v1/payouts"));
        assertEquals(RouteClass.PAYOUTS, RouteTable.resolve("/v1/payouts/pot_1"));
        assertEquals(RouteClass.TRANSFERS, RouteTable.resolve("/v1/transfers"));
        assertEquals(RouteClass.TRANSFERS, RouteTable.resolve("/v1/transfers/trf_1"));
        assertEquals(RouteClass.WALLETS, RouteTable.resolve("/v1/wallets"));
        assertEquals(RouteClass.WALLETS, RouteTable.resolve("/v1/wallets/wal_1/statement"));
        assertEquals(RouteClass.FX, RouteTable.resolve("/v1/fx"));
        assertEquals(RouteClass.FX, RouteTable.resolve("/v1/fx/quotes/fxq_1"));
        assertEquals(RouteClass.WEBHOOKS, RouteTable.resolve("/v1/webhook-endpoints"));
        assertEquals(RouteClass.WEBHOOKS, RouteTable.resolve("/v1/webhook-endpoints/wh_1"));
        assertEquals(RouteClass.API_KEYS, RouteTable.resolve("/v1/api-keys"));
        assertEquals(RouteClass.API_KEYS, RouteTable.resolve("/v1/api-keys/key_1/rotate"));
        assertEquals(RouteClass.SANDBOX, RouteTable.resolve("/sandbox"));
        assertEquals(RouteClass.SANDBOX, RouteTable.resolve("/sandbox/payments"));
        assertEquals(RouteClass.SANDBOX, RouteTable.resolve("/sandbox/payments/pay_1"));
    }

    @Test
    void prefixesMatchExactlyOrWithASlashNeverSubstring() {
        // /v1/pay would be a different, unknown surface (not a payments prefix)
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v1/pay"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v1/payments-extra"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v1/payment"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v2/payments"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/sandbox2"));
    }

    @Test
    void unknownMalformedAndForeignPathsResolveToUnknown() {
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v1"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/v1/unknown"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("/internal"));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve(""));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve(null));
        assertEquals(RouteClass.UNKNOWN, RouteTable.resolve("   "));
    }

    @Test
    void nonApiSurfacesBypassApiKeyAuth() {
        assertTrue(RouteTable.isNonApiSurface("/internal/events"));
        assertTrue(RouteTable.isNonApiSurface("/internal/whatever"));
        assertTrue(RouteTable.isNonApiSurface("/actuator/health"));
        assertTrue(RouteTable.isNonApiSurface("/actuator"));
        assertFalse(RouteTable.isNonApiSurface("/v1/payments"));
        assertFalse(RouteTable.isNonApiSurface("/sandbox/payments"));
        assertFalse(RouteTable.isNonApiSurface(null));
        assertFalse(RouteTable.isNonApiSurface("/"));
    }

    @Test
    void readVerbsNeedReadScopesMutatingVerbsNeedWriteScopes() {
        assertEquals(Optional.of(Scope.PAYMENTS_READ), RouteClass.PAYMENTS.requiredScope("GET"));
        assertEquals(Optional.of(Scope.PAYMENTS_WRITE), RouteClass.PAYMENTS.requiredScope("POST"));
        assertEquals(Optional.of(Scope.PAYMENTS_WRITE),
                RouteClass.PAYMENTS.requiredScope("DELETE"));
        assertEquals(Optional.of(Scope.PAYMENTS_WRITE), RouteClass.PAYMENTS.requiredScope("PUT"));

        assertEquals(Optional.of(Scope.PAYOUTS_READ), RouteClass.PAYOUTS.requiredScope("GET"));
        assertEquals(Optional.of(Scope.PAYOUTS_WRITE), RouteClass.PAYOUTS.requiredScope("POST"));

        assertEquals(Optional.of(Scope.FX_READ), RouteClass.FX.requiredScope("GET"));
        assertEquals(Optional.of(Scope.FX_WRITE), RouteClass.FX.requiredScope("POST"));

        // GET is case-insensitive on the method
        assertEquals(Optional.of(Scope.PAYMENTS_READ), RouteClass.PAYMENTS.requiredScope("get"));
    }

    @Test
    void transfersIsWriteOnlyAndWebhookAndApiKeySurfacesAreAlwaysManageScoped() {
        assertEquals(Optional.of(Scope.TRANSFERS_WRITE), RouteClass.TRANSFERS.requiredScope("GET"));
        assertEquals(Optional.of(Scope.TRANSFERS_WRITE),
                RouteClass.TRANSFERS.requiredScope("POST"));
        assertEquals(Optional.of(Scope.WEBHOOKS_MANAGE), RouteClass.WEBHOOKS.requiredScope("GET"));
        assertEquals(Optional.of(Scope.WEBHOOKS_MANAGE), RouteClass.WEBHOOKS.requiredScope("POST"));
        assertEquals(Optional.of(Scope.API_KEYS_MANAGE), RouteClass.API_KEYS.requiredScope("GET"));
        assertEquals(Optional.of(Scope.API_KEYS_MANAGE), RouteClass.API_KEYS.requiredScope("POST"));
    }

    @Test
    void walletsIsReadOnlyInV1SoMutationsHaveNoSatisfiableScope() {
        assertEquals(Optional.of(Scope.WALLETS_READ), RouteClass.WALLETS.requiredScope("GET"));
        assertTrue(RouteClass.WALLETS.requiredScope("POST").isEmpty(),
                "POST /v1/wallets must be fail-closed (no write scope exists)");
        assertTrue(RouteClass.WALLETS.requiredScope("PUT").isEmpty());
        assertTrue(RouteClass.WALLETS.requiredScope("DELETE").isEmpty());
    }

    @Test
    void sandboxUsesPaymentScopesAndUnknownNeverYieldsAScope() {
        assertEquals(Optional.of(Scope.PAYMENTS_READ), RouteClass.SANDBOX.requiredScope("GET"));
        assertEquals(Optional.of(Scope.PAYMENTS_WRITE), RouteClass.SANDBOX.requiredScope("POST"));
        assertTrue(RouteClass.UNKNOWN.requiredScope("GET").isEmpty());
        assertTrue(RouteClass.UNKNOWN.requiredScope("POST").isEmpty());
    }
}
