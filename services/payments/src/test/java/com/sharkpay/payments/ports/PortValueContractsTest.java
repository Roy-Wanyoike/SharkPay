package com.sharkpay.payments.ports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Value-level contracts of the ports (the hexagon's published types): the
 * idempotency StoredRequest shape, the risk decision's fail-closed tier
 * semantics, and the provider failure taxonomy constructors.
 */
class PortValueContractsTest {

    @Test
    void storedRequestsValidateTheirFingerprintAndEntity() {
        assertThat(new IdempotencyStore.StoredRequest("CREATE_PAYMENT|f", "pay_1")
                .requestFingerprint()).isEqualTo("CREATE_PAYMENT|f");
        assertThat(new IdempotencyStore.StoredRequest("f", "pay_1").entityId())
                .isEqualTo("pay_1");

        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest(null, "pay_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint");
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest(" ", "pay_1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint");
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest("f", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest("f", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
    }

    @Test
    void riskDecisionsCopyReasonsAndFailClosedOnUnknownTiers() {
        // null reasons normalize to the empty list; the list is defensive-copied
        RiskPort.RiskDecision nullReasons =
                new RiskPort.RiskDecision(RiskPort.Decision.DENY, null, 2);
        assertThat(nullReasons.reasons()).isEmpty();
        List<String> mutable = new java.util.ArrayList<>(List.of("r"));
        RiskPort.RiskDecision decision =
                new RiskPort.RiskDecision(RiskPort.Decision.REVIEW, mutable, 1);
        mutable.add("late");
        assertThat(decision.reasons()).containsExactly("r");

        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.ALLOW, List.of(), 2).allowed())
                .isTrue();
        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.DENY, List.of(), 2).allowed())
                .isFalse();
        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.REVIEW, List.of(), 2).allowed())
                .isFalse();

        // tier rank: null ranks lowest (fail closed), out-of-range clamps
        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.ALLOW, List.of(), null).tierRank())
                .isZero();
        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.ALLOW, List.of(), 5).tierRank())
                .isEqualTo(2);
        assertThat(new RiskPort.RiskDecision(RiskPort.Decision.ALLOW, List.of(), -3).tierRank())
                .isZero();
    }

    @Test
    void providerFailuresPreserveTheirMessageAndCause() {
        assertThat(new ProviderRejectedException("rail rejected").getMessage())
                .isEqualTo("rail rejected");

        ProviderUnavailableException withCause = new ProviderUnavailableException(
                "gateway timeout", new IllegalStateException("dial tcp: refused"));
        assertThat(withCause.getMessage()).isEqualTo("gateway timeout");
        assertThat(withCause.getCause()).isInstanceOf(IllegalStateException.class);
    }
}
