package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fail-closed scope catalog (docs/API-CONTRACTS.md §5). */
class ScopeTest {

    @Test
    void parsesEveryCatalogScope() {
        assertEquals(Scope.PAYMENTS_READ, Scope.parse("payments:read"));
        assertEquals(Scope.PAYMENTS_WRITE, Scope.parse("payments:write"));
        assertEquals(Scope.PAYOUTS_READ, Scope.parse("payouts:read"));
        assertEquals(Scope.PAYOUTS_WRITE, Scope.parse("payouts:write"));
        assertEquals(Scope.TRANSFERS_WRITE, Scope.parse("transfers:write"));
        assertEquals(Scope.WALLETS_READ, Scope.parse("wallets:read"));
        assertEquals(Scope.FX_READ, Scope.parse("fx:read"));
        assertEquals(Scope.FX_WRITE, Scope.parse("fx:write"));
        assertEquals(Scope.WEBHOOKS_MANAGE, Scope.parse("webhooks:manage"));
        assertEquals(Scope.OPS_READ, Scope.parse("ops:read"));
        assertEquals(Scope.API_KEYS_MANAGE, Scope.parse("apikeys:manage"));
    }

    @Test
    void unknownScopesAreRejectedFailClosed() {
        assertThrows(UnknownScopeException.class, () -> Scope.parse("payment:write"));
        assertThrows(UnknownScopeException.class, () -> Scope.parse("payments:admin"));
        assertThrows(UnknownScopeException.class, () -> Scope.parse("*"));
        assertThrows(UnknownScopeException.class, () -> Scope.parse(""));
        UnknownScopeException error = assertThrows(UnknownScopeException.class,
                () -> Scope.parse("payment:write"));
        assertEquals("payment:write", error.offendingScope());
    }

    @Test
    void wireNameRoundTripIsStable() {
        for (Scope scope : Scope.values()) {
            assertEquals(scope, Scope.parse(scope.wireName()));
        }
    }

    @Test
    void parseAllPreservesOrderAndRejectsUnknowns() {
        Set<Scope> scopes = Scope.parseAll(List.of("payments:read", "payments:write"));
        assertEquals(Set.of(Scope.PAYMENTS_READ, Scope.PAYMENTS_WRITE), scopes);
        assertThrows(UnknownScopeException.class,
                () -> Scope.parseAll(List.of("payments:read", "bogus:scope")));
    }

    @Test
    void toWireNamesRendersTheSet() {
        Set<String> wireNames = Scope.toWireNames(Set.of(Scope.PAYMENTS_READ, Scope.OPS_READ));
        assertEquals(2, wireNames.size());
        assertTrue(wireNames.contains("payments:read"));
        assertTrue(wireNames.contains("ops:read"));
        assertFalse(wireNames.contains("fx:read"));
    }
}
