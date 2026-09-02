package com.sharkpay.gateway.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server-assigned request ids (common.yaml: {@code req_[0-9A-Za-z]+}).
 */
class IdsTest {

    @Test
    void requestIdsCarryTheContractPrefixAndAlphabet() {
        for (int i = 0; i < 50; i++) {
            String requestId = Ids.requestId();
            assertTrue(requestId.startsWith("req_"), requestId);
            assertTrue(requestId.matches("^req_[0-9A-Za-z]+$"), requestId);
        }
    }

    @Test
    void requestIdsAreUnique() {
        assertNotEquals(Ids.requestId(), Ids.requestId());
    }
}
