package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire enums: every enum round-trips through its wire name and rejects
 * unknown names (fail-closed parsing — a typo can never become a state).
 */
class WireEnumsTest {

    @Test
    void apiKeyStatusRoundTripsAndRejectsUnknown() {
        for (ApiKeyStatus status : ApiKeyStatus.values()) {
            assertEquals(status, ApiKeyStatus.fromWire(status.wireName()));
        }
        assertEquals("active", ApiKeyStatus.ACTIVE.wireName());
        assertEquals("rotating", ApiKeyStatus.ROTATING.wireName());
        assertEquals("revoked", ApiKeyStatus.REVOKED.wireName());
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStatus.fromWire("paused"));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStatus.fromWire(""));
        assertThrows(IllegalArgumentException.class, () -> ApiKeyStatus.fromWire(null));
    }

    @Test
    void subscriptionStateRoundTripsAndOnlyActiveAcceptsEvents() {
        for (SubscriptionState state : SubscriptionState.values()) {
            assertEquals(state, SubscriptionState.fromWire(state.wireName()));
        }
        assertEquals("active", SubscriptionState.ACTIVE.wireName());
        assertEquals("paused", SubscriptionState.PAUSED.wireName());
        assertEquals("dead", SubscriptionState.DEAD.wireName());
        assertEquals("deleted", SubscriptionState.DELETED.wireName());
        assertThrows(IllegalArgumentException.class, () -> SubscriptionState.fromWire("revoked"));

        assertTrue(SubscriptionState.ACTIVE.acceptsEvents());
        assertFalse(SubscriptionState.PAUSED.acceptsEvents());
        assertFalse(SubscriptionState.DEAD.acceptsEvents());
        assertFalse(SubscriptionState.DELETED.acceptsEvents());
    }

    @Test
    void deliveryStateRoundTripsAndRejectsUnknown() {
        for (DeliveryState state : DeliveryState.values()) {
            assertEquals(state, DeliveryState.fromWire(state.wireName()));
        }
        assertEquals("pending", DeliveryState.PENDING.wireName());
        assertEquals("delivered", DeliveryState.DELIVERED.wireName());
        assertEquals("dead", DeliveryState.DEAD.wireName());
        assertThrows(IllegalArgumentException.class, () -> DeliveryState.fromWire("failed"));
    }
}
